package com.unoone.agent.localbrain

import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local LLM brain for UnoOne.
 *
 * Thin wrapper around [GemmaPlanner], which loads a Gemma `.litertlm` model via LiteRT-LM and
 * performs manual tool calling. Profile-aware: callers can load either Gemma 4 E2B or the legacy
 * Gemma 3n E4B through the same interface by passing a [BrainModelSpec].
 *
 * The old ONNX shell has been removed. RuleBasedParser remains the fast offline
 * fallback when no model is loaded.
 */
class LocalBrain {

    private val json = Json { ignoreUnknownKeys = true }
    internal val planner = GemmaPlanner()

    fun isModelLoaded(): Boolean = planner.isLoaded()

    /** Backend the model loaded on ("GPU"/"CPU"), or "" if not loaded. */
    fun activeBackend(): String = planner.activeBackend()

    /** Last load error (empty on success) — surfaces device-compatibility status to the UI. */
    fun lastLoadError(): String = planner.lastLoadError()

    /** The profile currently loaded, or null when no model is loaded. */
    fun loadedProfile(): BrainModelSpec? = planner.loadedProfile()

    /** Legacy single-path load (default profile, Gemma 3n E4B). */
    suspend fun loadModel(modelPath: String): Result<Unit> = planner.load(modelPath)

    /** Profile-aware load — loads [modelPath] as [spec] (Gemma 4 E2B or Gemma 3n E4B). */
    suspend fun loadModel(modelPath: String, spec: BrainModelSpec): Result<Unit> =
        planner.load(modelPath, spec)

    fun unloadModel() {
        Logger.i("LocalBrain: unloading Gemma model")
        planner.close()
    }

    /**
     * Run inference with a full context snapshot.
     */
    suspend fun runInference(prompt: String, context: ContextSnapshot): Result<ToolCall> {
        return planner.plan(prompt, context)
    }

    /**
     * Streaming variant of [runInference]: same validated single-`ToolCall` result, but invokes
     * [onDelta] with each incremental text delta as the model generates, so the caller can surface
     * partial output to the UI timeline. Device-time-only (not JVM-testable); the pure delta
     * reduction is JVM-tested in [com.unoone.agent.core.agent.StreamingTextReducer].
     */
    suspend fun runInferenceStreaming(
        prompt: String,
        context: ContextSnapshot,
        onDelta: (String) -> Unit
    ): Result<ToolCall> {
        return planner.planStreaming(prompt, context, onDelta)
    }

    /**
     * ReAct "Observe" step: feeds the [observation] (result of [prevTool]) back into the live
     * conversation and returns the model's next proposed, validated tool call. Only meaningful when
     * a model is loaded; the caller ([com.unoone.agent.AgentOrchestrator]) only invokes this inside
     * the bounded ReAct loop after the first call was planned by the LLM. Device-time verified.
     */
    suspend fun planNext(prevTool: String, observation: String): Result<ToolCall> {
        return planner.planNext(prevTool, observation)
    }

    /**
     * Second on-device safety-judge pass over a proposed action. Returns a [SafetyVerdict] the
     * orchestrator merges (escalate-only) with the keyword tier. Device-time verified.
     */
    suspend fun judgeSafety(toolName: String, argsJson: String, inputText: String): Result<SafetyVerdict> {
        return planner.judgeSafety(toolName, argsJson, inputText)
    }

    /**
     * Multimodal vision description of a screenshot. INACTIVE with the shipped text-only models
     * (no vision weights) — the caller gates this behind a vision-capable model check and falls back
     * to [com.unoone.agent.core.agent.SceneDescriptionBuilder] on any Error. Device-time-only.
     */
    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> =
        planner.describeSceneWithVision(imageBytes, aspect)

    /**
     * Parses a raw JSON tool call string into a [ToolCall].
     * Kept as a defensive fallback for string output from any future model path.
     */
    fun parseToolCall(output: String): Result<ToolCall> {
        return try {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start == -1 || end == -1) return Result.Error("No valid JSON in model output")

            val jsonStr = output.substring(start, end + 1)
            val element = json.parseToJsonElement(jsonStr)
            val obj = element.jsonObject
            val tool = obj["tool"]?.jsonPrimitive?.content ?: return Result.Error("Missing 'tool' field")
            val args = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
            Result.Success(ToolCall(tool, args))
        } catch (e: Exception) {
            Result.Error("Failed to parse tool call: ${e.message}")
        }
    }
}
