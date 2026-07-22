package com.unoone.agent.browser

import android.content.Context
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.BrainModelRegistry
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
 * Exclusive ownership of Gemma 4 E4B while the local Page Agent is active.
 *
 * Mobile memory must never hold separate phone-agent and browser-agent copies of E4B. Acquiring this
 * lease closes the main UnoOne brain, loads the same integrity-verified artifact into a browser-only
 * planner, and exposes a [BrowserModelPort]. Normal release restores the main brain when it was
 * loaded before acquisition. Emergency release skips restoration so memory-pressure handling cannot
 * immediately reallocate the model it just freed.
 */
class SecureBrowserModelLease(
    context: Context,
    private val orchestrator: AgentOrchestrator
) {

    private val modelManager = ModelManager(context.applicationContext)
    private val planner = PageAgentGemmaPlanner()
    private val mutex = Mutex()

    @Volatile private var active = false
    private var restoreMainBrain = false
    private var leasedModelPath: String? = null

    fun isActive(): Boolean = active
    fun activeBackend(): String = planner.activeBackend()
    fun lastLoadError(): String = planner.lastLoadError()

    suspend fun acquire(): Result<BrowserModelPort> = mutex.withLock {
        if (active) return@withLock Result.Error("Secure Browser already owns the Gemma model")

        val spec = BrainModelRegistry.GEMMA_4_E4B
        val path = withContext(Dispatchers.IO) { modelManager.getLlmModelPath(spec) }
            ?: return@withLock Result.Error(
                "Gemma 4 E4B is not installed or failed integrity verification. Install the exact Android .litertlm artifact before starting Secure Browser."
            )

        val mainWasLoaded = orchestrator.isLlmLoaded()
        if (!ExclusiveBrainLeaseState.acquire(OWNER_ID)) {
            return@withLock Result.Error(
                "Gemma is already reserved by ${ExclusiveBrainLeaseState.currentOwner() ?: "another UnoOne mode"}."
            )
        }

        restoreMainBrain = mainWasLoaded
        leasedModelPath = path
        try {
            if (restoreMainBrain) orchestrator.unloadLlmModel()

            val load = planner.load(path, spec)
            if (load is Result.Error) {
                if (restoreMainBrain) {
                    orchestrator.loadLlmModelUnderLease(path, spec, OWNER_ID)
                }
                restoreMainBrain = false
                leasedModelPath = null
                ExclusiveBrainLeaseState.release(OWNER_ID)
                return@withLock load
            }

            active = true
            Result.Success(
            BrowserModelPort { invocation ->
                when (val result = planner.plan(
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
            planner.requestCancel("browser acquisition cancelled")
            withContext(NonCancellable + Dispatchers.IO) {
                val browserClosed = runCatching { planner.close() }.getOrDefault(false)
                if (browserClosed) {
                    if (restoreMainBrain) {
                        runCatching { orchestrator.loadLlmModelUnderLease(path, spec, OWNER_ID) }
                    }
                    active = false
                    restoreMainBrain = false
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
                val browserClosed = runCatching { planner.close() }.getOrDefault(false)
                if (browserClosed) {
                    if (restoreMainBrain) {
                        runCatching { orchestrator.loadLlmModelUnderLease(path, spec, OWNER_ID) }
                    }
                    active = false
                    restoreMainBrain = false
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

        planner.requestCancel("browser lease released")
        val browserClosed = planner.close()
        if (!browserClosed) {
            return@withLock Result.Error(
                "Secure Browser native inference did not stop; phone brain was not restored"
            )
        }
        active = false
        val path = leasedModelPath
        val shouldRestore = restore && restoreMainBrain
        leasedModelPath = null
        restoreMainBrain = false
        if (shouldRestore && path != null) {
            val restored = orchestrator.loadLlmModelUnderLease(
                path,
                BrainModelRegistry.GEMMA_4_E4B,
                OWNER_ID
            )
            ExclusiveBrainLeaseState.release(OWNER_ID)
            return@withLock restored
        }
        ExclusiveBrainLeaseState.release(OWNER_ID)
        Result.Success(Unit)
    }

    companion object {
        const val OWNER_ID = "secure-browser-page-agent"
    }
}
