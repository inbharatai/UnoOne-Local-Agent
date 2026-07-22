package com.unoone.agent.localbrain

import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local LLM facade for UnoOne.
 *
 * This is a thin wrapper around [GemmaPlanner], which loads the sole integrity-verified Gemma 4 E4B
 * `.litertlm` model via LiteRT-LM and performs manual, schema-validated tool calling. Deterministic
 * Android commands continue to use the rule-based route before model inference.
 */
class LocalBrain {

    private val json = Json { ignoreUnknownKeys = true }
    internal val planner = GemmaPlanner()

    fun isModelLoaded(): Boolean = planner.isLoaded()

    /** Backend the model loaded on (`GPU` or `CPU`), or blank when not loaded. */
    fun activeBackend(): String = planner.activeBackend()

    /** Last load error, blank after a successful load. */
    fun lastLoadError(): String = planner.lastLoadError()

    /** The profile currently loaded, or null when no model is loaded. */
    fun loadedProfile(): BrainModelSpec? = planner.loadedProfile()

    /** Convenience load using the sole E4B profile. */
    suspend fun loadModel(modelPath: String): Result<Unit> = planner.load(modelPath)

    /** Loads [modelPath] using the explicit E4B [spec]. */
    suspend fun loadModel(modelPath: String, spec: BrainModelSpec): Result<Unit> =
        planner.load(modelPath, spec)

    suspend fun unloadModel() {
        Logger.i("LocalBrain: unloading Gemma model")
        planner.close()
    }

    fun cancelInference(reason: String = "external stop") = planner.requestCancel(reason)

    /** Runs one planning turn with a bounded context snapshot. */
    suspend fun runInference(prompt: String, context: ContextSnapshot): Result<ToolCall> =
        planner.plan(prompt, context)

    /**
     * Streaming variant of [runInference]. It returns the same validated single [ToolCall] while
     * forwarding incremental text deltas to the UI timeline.
     */
    suspend fun runInferenceStreaming(
        prompt: String,
        context: ContextSnapshot,
        onDelta: (String) -> Unit
    ): Result<ToolCall> = planner.planStreaming(prompt, context, onDelta)

    /**
     * Bounded observe step for an agent task. The previous verified tool result is returned to the
     * current planning conversation and the next canonical call is validated before execution.
     */
    suspend fun planNext(prevTool: String, observation: String): Result<ToolCall> =
        planner.planNext(prevTool, observation)

    /**
     * On-device safety-judge pass. The orchestrator uses the verdict only to escalate the native
     * policy; model output can never weaken the deterministic safety tier.
     */
    suspend fun judgeSafety(
        toolName: String,
        argsJson: String,
        inputText: String
    ): Result<SafetyVerdict> = planner.judgeSafety(toolName, argsJson, inputText)

    /** Tool-less conversational lane for action-free questions. */
    suspend fun chat(command: String, responseLanguage: String = ""): Result<String> =
        planner.chat(command, responseLanguage)

    /**
     * Optional multimodal scene path. The shipped E4B artifact is text-only, so callers keep this
     * gated behind a vision-capable profile and fall back to deterministic Accessibility/OCR output.
     */
    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> =
        planner.describeSceneWithVision(imageBytes, aspect)

    /**
     * Parses a raw JSON tool-call envelope. Retained only as a defensive fallback for a future model
     * path; the active LiteRT-LM route uses native structured tool calls and canonical validation.
     */
    fun parseToolCall(output: String): Result<ToolCall> {
        return try {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start == -1 || end == -1) return Result.Error("No valid JSON in model output")

            val jsonString = output.substring(start, end + 1)
            val element = json.parseToJsonElement(jsonString)
            val obj = element.jsonObject
            val tool = obj["tool"]?.jsonPrimitive?.content
                ?: return Result.Error("Missing 'tool' field")
            val args = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
            Result.Success(ToolCall(tool, args))
        } catch (e: Exception) {
            Result.Error("Failed to parse tool call: ${e.message}")
        }
    }
}
