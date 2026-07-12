package com.unoone.agent.browser

import android.content.Context
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.Result
import com.unoone.agent.localbrain.PageAgentGemmaPlanner
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.securebrowser.BrowserModelPort
import com.unoone.agent.securebrowser.PageAgentModelDecision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exclusive ownership of the Gemma 4 model while Alibaba PageAgent is active.
 *
 * Mobile memory cannot safely hold separate phone-agent and browser-agent copies of Gemma. Acquiring
 * this lease closes the main UnoOne brain, loads the same qualified artifact into a browser-only
 * planner, and exposes a [BrowserModelPort]. Releasing closes the browser planner and restores the
 * main brain only when it was loaded before acquisition.
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

        val spec = BrainModelRegistry.GEMMA_4_E2B
        val path = modelManager.getLlmModelPath(spec)
            ?: return@withLock Result.Error(
                "Gemma 4 E2B is not installed. Add a qualified .litertlm artifact before starting Secure Browser."
            )

        restoreMainBrain = orchestrator.isLlmLoaded()
        leasedModelPath = path
        if (restoreMainBrain) orchestrator.unloadLlmModel()

        val load = planner.load(path, spec)
        if (load is Result.Error) {
            if (restoreMainBrain) orchestrator.loadLlmModel(path, spec)
            restoreMainBrain = false
            leasedModelPath = null
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
    }

    suspend fun release(): Result<Unit> = mutex.withLock {
        if (!active && leasedModelPath == null) return@withLock Result.Success(Unit)

        planner.close()
        active = false
        val path = leasedModelPath
        val shouldRestore = restoreMainBrain
        leasedModelPath = null
        restoreMainBrain = false

        if (shouldRestore && path != null) {
            return@withLock orchestrator.loadLlmModel(path, BrainModelRegistry.GEMMA_4_E2B)
        }
        Result.Success(Unit)
    }
}
