package com.unoone.agent.brain

import com.unoone.agent.resolveBrainLoadPath
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.serialization.json.jsonPrimitive

data class BrainSelfTestProbe(
    val id: String,
    val expectedTool: String,
    val actualTool: String?,
    val passed: Boolean,
    val detail: String
)

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
    val probes: List<BrainSelfTestProbe> = emptyList(),
    val elapsedMs: Long,
    val message: String
)

/**
 * On-device self-test for a brain profile. Loads the profile's integrity-verified runtime artifact, records the
 * backend it loaded on + load latency + any load error, then runs strict **read-only** planning
 * probes through the model-only path. Nothing is executed — this never performs a phone action (no safety gate, no
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
        val path = modelManager.resolveBrainLoadPath(spec)
        if (path == null) {
            return BrainSelfTestResult(
                spec.manifestId, spec.displayName,
                installed = false, loaded = false, backend = "", loadError = "",
                proposedTool = null, toolAccepted = false, probes = emptyList(), elapsedMs = 0,
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
                proposedTool = null, toolAccepted = false, probes = emptyList(), elapsedMs = loadMs,
                message = "${spec.displayName} failed to load: $err"
            )
        }

        val backend = orchestrator.loadedBrainBackend()
        val probeResults = PROBES.map { probe ->
            val result = orchestrator.planLlmToolCall(probe.prompt)
            val call = (result as? Result.Success)?.data
            val passed = call != null && BrainSelfTestPolicy.validate(probe.id, call)
            BrainSelfTestProbe(
                id = probe.id,
                expectedTool = probe.expectedTool,
                actualTool = call?.tool,
                passed = passed,
                detail = if (passed) "exact tool and arguments accepted" else
                    (result as? Result.Error)?.message ?: "wrong tool or arguments"
            )
        }
        val proposedTool = probeResults.firstOrNull()?.actualTool
        val toolAccepted = backend.isNotBlank() &&
            orchestrator.loadedBrainProfile()?.manifestId == spec.manifestId &&
            probeResults.all { it.passed }
        val totalMs = System.currentTimeMillis() - t0
        val probeMsg = "${probeResults.count { it.passed }}/${probeResults.size} strict read-only probes passed"

        // Best-effort restore of the previously-active brain if the test loaded a different one.
        if (previous != null && previous.manifestId != spec.manifestId) {
            val prevPath = modelManager.resolveBrainLoadPath(previous)
            if (prevPath != null) {
                runCatching { orchestrator.loadLlmModel(prevPath, previous) }
            }
        }

        return BrainSelfTestResult(
            spec.manifestId, spec.displayName,
            installed = true, loaded = true, backend = backend, loadError = "",
            proposedTool = proposedTool, toolAccepted = toolAccepted, probes = probeResults, elapsedMs = totalMs,
            message = "${spec.displayName} loaded on $backend in ${loadMs}ms; $probeMsg."
        )
    }

    companion object {
        private const val SUMMARY_SENTENCE = "violet cranes cross the quiet lake at dawn"

        private data class Probe(
            val id: String,
            val prompt: String,
            val expectedTool: String
        )

        private val PROBES = listOf(
            Probe(
                "summarize",
                "Summarize this exact text: $SUMMARY_SENTENCE",
                "summarize_text"
            ),
            Probe("open-app", "Open WhatsApp", "open_app"),
            Probe(
                "missing-recipient",
                "Draft an email with subject status and body the build is ready",
                "speak_response"
            )
        )
    }
}

internal object BrainSelfTestPolicy {
    private const val SUMMARY_SENTENCE = "violet cranes cross the quiet lake at dawn"
    private val emailPattern = Regex(
        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
        RegexOption.IGNORE_CASE
    )

    fun validate(probeId: String, call: ToolCall): Boolean = when (probeId) {
        "summarize" -> call.tool == "summarize_text" &&
            call.args["text"]?.jsonPrimitive?.content?.trim() == SUMMARY_SENTENCE
        "open-app" -> call.tool == "open_app" &&
            call.args["app_name"]?.jsonPrimitive?.content?.trim()
                ?.equals("WhatsApp", ignoreCase = true) == true
        "missing-recipient" -> {
            val text = call.args["text"]?.jsonPrimitive?.content?.lowercase().orEmpty()
            call.tool == "speak_response" &&
                listOf("recipient", "email address", "who").any(text::contains) &&
                !emailPattern.containsMatchIn(text)
        }
        else -> false
    }
}
