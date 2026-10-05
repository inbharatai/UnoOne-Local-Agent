package com.unoone.agent.browser

import android.content.Context
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.resolveBrainLoadPath
import com.unoone.agent.core.model.BrainRuntime
import com.unoone.agent.localbrain.PageAgentPlan
import com.unoone.agent.localbrain.QwenPageAgentPlanner
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.model.Result
import com.unoone.agent.localbrain.PageAgentGemmaPlanner
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.securebrowser.BrowserModelPort
import com.unoone.agent.securebrowser.PageAgentModelDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exclusive ownership of the selected local brain profile while the local Page Agent is active.
 *
 * Mobile memory must never hold separate phone-agent and browser-agent copies of a model. Acquiring this
 * lease closes the main UnoOne brain, loads the same integrity-verified artifact into a browser-only
 * planner, and exposes a [BrowserModelPort]. Normal release restores the main brain when it was
 * loaded before acquisition. Emergency release skips restoration so memory-pressure handling cannot
 * immediately reallocate the model it just freed.
 */
class SecureBrowserModelLease(
    context: Context,
    private val orchestrator: AgentOrchestrator,
    private val selectedProfile: () -> BrainModelSpec? = { null }
) {

    private val modelManager = ModelManager(context.applicationContext)
    private val gemma by lazy { PageAgentGemmaPlanner() }
    private val qwen by lazy { QwenPageAgentPlanner(residentOwner = OWNER_ID) }
    @Volatile private var runtime = BrainRuntime.LITERT_LM
    private val mutex = Mutex()

    @Volatile private var active = false
    private var previousPhone: BrowserLeasePolicy.PhoneModel? = null
    private var leasedModelPath: String? = null

    @Volatile private var stopping = false
    fun requestStop() { stopping = true; requestCancel("browser task stop") }
    suspend fun awaitStopAcknowledgement(): Boolean {
        val acknowledged = cancelAndAwaitIdle()
        if (acknowledged) stopping = false
        return acknowledged
    }

    fun isActive(): Boolean = active
    fun activeBackend(): String = if (runtime == BrainRuntime.MNN) qwen.activeBackend() else gemma.activeBackend()
    fun configReceipt(): String? = if (runtime == BrainRuntime.MNN) qwen.configReceipt() else null
    fun lastLoadError(): String = if (runtime == BrainRuntime.MNN) qwen.lastLoadError() else gemma.lastLoadError()

    suspend fun acquire(): Result<BrowserModelPort> = mutex.withLock {
        if (!BrowserLeasePolicy.canAcquire(active || ExclusiveBrainLeaseState.isActive())) return@withLock Result.Error("A local brain model is already exclusively reserved")

        // Snapshot restoration identity before unload; never substitute the browser path.
        val phoneResident = orchestrator.isPhoneBrainResident()
        val prior = if (phoneResident) {
            val priorPath = orchestrator.loadedBrainPath()
                ?: return@withLock Result.Error("Phone artifact identity unavailable; browser acquisition refused")
            val priorSpec = orchestrator.loadedBrainProfile()
                ?: return@withLock Result.Error("Phone profile unavailable; browser acquisition refused")
            BrowserLeasePolicy.PhoneModel(priorPath, priorSpec)
        } else null
        val selected = try { selectedProfile() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock Result.Error("Brain selection is not ready; try again after initialization") }
        val spec = selected ?: BrowserLeasePolicy.browserProfile(prior, null)
        val path = withContext(Dispatchers.IO) { modelManager.resolveBrainLoadPath(spec) }
            ?: return@withLock Result.Error("${spec.displayName} is not installed or failed integrity verification")
        if (!ExclusiveBrainLeaseState.acquire(OWNER_ID)) {
            return@withLock Result.Error("Local brain is already reserved by another UnoOne mode")
        }

        runtime = spec.runtime
        previousPhone = prior
        leasedModelPath = path
        var browserLoadStarted = false
        try {
            // Always run the native unload barrier, including when a load was in flight.
            val unloaded = orchestrator.unloadLlmModel()
            if (!BrowserLeasePolicy.canLoadBrowser(unloaded, orchestrator.isPhoneBrainResident())) {
                previousPhone = null
                leasedModelPath = null
                ExclusiveBrainLeaseState.release(OWNER_ID)
                return@withLock Result.Error("Phone native engine remains resident; browser acquisition refused")
            }

            browserLoadStarted = true
            val load = loadBrowser(path, spec)
            if (load is Result.Error) {
                if (!closeBrowser()) {
                    active = true
                    return@withLock Result.Error("Browser cleanup refused; exclusive lease retained")
                }
                restorePreviousPhone()
                previousPhone = null
                leasedModelPath = null
                ExclusiveBrainLeaseState.release(OWNER_ID)
                return@withLock load
            }

            active = true
            Result.Success(
            BrowserModelPort { invocation ->
                if (stopping) return@BrowserModelPort kotlin.Result.failure(IllegalStateException("Native browser stop is not yet acknowledged"))
                when (val result = planBrowser(
                    pageAgentSystemPrompt = invocation.systemPrompt,
                    pageAgentUserPrompt = invocation.userPrompt,
                    macroToolSchemaJson = invocation.macroToolSchemaJson,
                    maxOutputTokens = invocation.maxOutputTokens
                )) {
                    is Result.Success -> kotlin.Result.success(
                        PageAgentModelDecision(
                            evaluationPreviousGoal = result.data.evaluationPreviousGoal,
                            memory = result.data.memory,
                            nextGoal = result.data.nextGoal,
                            actionName = result.data.actionName,
                            actionArgumentsJson = result.data.actionArgumentsJson
                        )
                    )
                    is Result.Error -> kotlin.Result.failure(
                        result.cause ?: IllegalStateException(result.message)
                    )
                }
            }
            )
        } catch (cancelled: CancellationException) {
            requestCancel("browser acquisition cancelled")
            withContext(NonCancellable + Dispatchers.IO) {
                val browserClosed = !browserLoadStarted || runCatching { closeBrowser() }.getOrDefault(false)
                if (browserClosed) {
                    runCatching { restorePreviousPhone() }
                    active = false
                    previousPhone = null
                    leasedModelPath = null
                    ExclusiveBrainLeaseState.release(OWNER_ID)
                } else {
                    // Native work is still alive. Keep the exclusive lease so no phone engine can
                    // be created beside it; a later explicit release will retry cancellation.
                    active = true
                    leasedModelPath = path
                }
            }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                val browserClosed = !browserLoadStarted || runCatching { closeBrowser() }.getOrDefault(false)
                if (browserClosed) {
                    runCatching { restorePreviousPhone() }
                    active = false
                    previousPhone = null
                    leasedModelPath = null
                    ExclusiveBrainLeaseState.release(OWNER_ID)
                } else {
                    active = true
                    leasedModelPath = path
                }
            }
            Result.Error("Secure Browser model transition failed: ${error.message}", error)
        }
    }

    suspend fun release(restore: Boolean = true): Result<Unit> = mutex.withLock {
        if (!active && leasedModelPath == null) {
            ExclusiveBrainLeaseState.release(OWNER_ID)
            return@withLock Result.Success(Unit)
        }

        requestCancel("browser lease released")
        val browserClosed = closeBrowser()
        if (!browserClosed) {
            return@withLock Result.Error(
                "Secure Browser native inference did not stop; phone brain was not restored"
            )
        }
        active = false
        // Keep ownership until restoration finishes, including cancellation/exception cleanup.
        withContext(NonCancellable) {
            try {
                if (restore) restorePreviousPhone() else Result.Success(Unit)
            } finally {
                leasedModelPath = null
                previousPhone = null
                ExclusiveBrainLeaseState.release(OWNER_ID)
            }
        }
    }

    /** Only invoked after browser close proof. Never create a second phone engine on unload refusal. */
    private suspend fun restorePreviousPhone(): Result<Unit> {
        val prior = BrowserLeasePolicy.restoration(
            previousPhone, browserClosed = true, phoneResident = orchestrator.isPhoneBrainResident()
        ) ?: return Result.Success(Unit)
        return orchestrator.loadLlmModelUnderLease(prior.path, prior.spec, OWNER_ID)
    }

    private fun requestCancel(reason: String) {
        if (runtime == BrainRuntime.MNN) qwen.requestCancel(reason) else gemma.requestCancel(reason)
    }

    private suspend fun cancelAndAwaitIdle(): Boolean =
        if (runtime == BrainRuntime.MNN) qwen.cancelAndAwaitIdle() else gemma.cancelAndAwaitIdle()

    private suspend fun closeBrowser(): Boolean =
        if (runtime == BrainRuntime.MNN) qwen.close() else gemma.close()

    private suspend fun loadBrowser(path: String, spec: BrainModelSpec): Result<Unit> =
        if (runtime == BrainRuntime.MNN) qwen.load(path, spec, OWNER_ID) else gemma.load(path, spec, OWNER_ID)

    private suspend fun planBrowser(
        pageAgentSystemPrompt: String,
        pageAgentUserPrompt: String,
        macroToolSchemaJson: String,
        maxOutputTokens: Int
    ): Result<PageAgentPlan> = if (runtime == BrainRuntime.MNN) {
        qwen.plan(pageAgentSystemPrompt, pageAgentUserPrompt, macroToolSchemaJson, maxOutputTokens)
    } else {
        gemma.plan(pageAgentSystemPrompt, pageAgentUserPrompt, macroToolSchemaJson, maxOutputTokens)
    }

    companion object {
        const val OWNER_ID = "secure-browser"
    }
}
