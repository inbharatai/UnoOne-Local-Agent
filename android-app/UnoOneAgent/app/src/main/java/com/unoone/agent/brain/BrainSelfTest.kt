package com.unoone.agent.brain

import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.Result
import com.unoone.agent.modelmanager.ModelManager

/**
 * The outcome of a [BrainSelfTest] run — surfaced to the Model Status UI.
 */
data class BrainSelfTestResult(
    val manifestId: String,
    val displayName: String,
    val installed: Boolean,
    val loaded: Boolean,
    val backend: String,
    val loadError: String,
    val proposedTool: String?,
    val toolAccepted: Boolean,
    val elapsedMs: Long,
    val message: String
)

/**
 * On-device self-test for a brain profile. Loads the profile's installed `.litertlm`, records the
 * backend it loaded on + load latency + any load error, then runs a single **read-only** planning
 * probe (a command the rule-based parser does NOT match, so it forces the Gemma planner) and records
 * the proposed tool. Nothing is executed — this never performs a phone action (no safety gate, no
 * permissions, no [com.unoone.agent.execution.ActionExecutor]).
 *
 * The probe command is deliberately chosen to bypass [com.unoone.agent.localbrain.RuleBasedParser]
 * (no note/search/open/calendar/screen keyword) so it exercises the LLM path, verifying the model
 * loads AND produces an accepted tool call on this device — the literal device-verification gate.
 *
 * If a different brain was active before the test, it is reloaded afterward so the user's active
 * brain is not silently switched (a best-effort restore; if it fails the tested brain stays loaded).
 */
class BrainSelfTest(
    private val orchestrator: AgentOrchestrator,
    private val modelManager: ModelManager
) {

    suspend fun run(spec: BrainModelSpec): BrainSelfTestResult {
        val path = modelManager.getLlmModelPath(spec)
        if (path == null) {
            return BrainSelfTestResult(
                spec.manifestId, spec.displayName,
                installed = false, loaded = false, backend = "", loadError = "",
                proposedTool = null, toolAccepted = false, elapsedMs = 0,
                message = "${spec.displayName} is not installed. Install it first, then run the self-test."
            )
        }

        val previous = orchestrator.loadedBrainProfile()
        val t0 = System.currentTimeMillis()
        val load = orchestrator.loadLlmModel(path, spec)
        val loadMs = System.currentTimeMillis() - t0
        val loaded = load is Result.Success && orchestrator.isLlmLoaded()
        if (!loaded) {
            val err = (load as? Result.Error)?.message ?: orchestrator.lastBrainLoadError()
            return BrainSelfTestResult(
                spec.manifestId, spec.displayName,
                installed = true, loaded = false, backend = "", loadError = err,
                proposedTool = null, toolAccepted = false, elapsedMs = loadMs,
                message = "${spec.displayName} failed to load: $err"
            )
        }

        val backend = orchestrator.loadedBrainBackend()
        // Read-only planning probe — forces the LLM path (no rule matches this command).
        val probe = orchestrator.planToolCall(PROBE_COMMAND)
        val proposedTool = (probe as? Result.Success)?.data?.tool
        val toolAccepted = proposedTool != null && CanonicalToolRegistry.isKnown(proposedTool)
        val totalMs = System.currentTimeMillis() - t0
        val probeMsg = when {
            proposedTool == null -> "no tool proposed (${(probe as? Result.Error)?.message ?: "?"})"
            toolAccepted -> "proposed '$proposedTool' (accepted)"
            else -> "proposed '$proposedTool' (REJECTED — non-canonical)"
        }

        // Best-effort restore of the previously-active brain if the test loaded a different one.
        if (previous != null && previous.manifestId != spec.manifestId) {
            val prevPath = modelManager.getLlmModelPath(previous)
            if (prevPath != null) {
                runCatching { orchestrator.loadLlmModel(prevPath, previous) }
            }
        }

        return BrainSelfTestResult(
            spec.manifestId, spec.displayName,
            installed = true, loaded = true, backend = backend, loadError = "",
            proposedTool = proposedTool, toolAccepted = toolAccepted, elapsedMs = totalMs,
            message = "${spec.displayName} loaded on $backend in ${loadMs}ms; $probeMsg."
        )
    }

    companion object {
        /** Probe command — bypasses RuleBasedParser (no rule keyword) to force the LLM planner. */
        const val PROBE_COMMAND = "summarize this text: the quick brown fox jumps over the lazy dog"
    }
}