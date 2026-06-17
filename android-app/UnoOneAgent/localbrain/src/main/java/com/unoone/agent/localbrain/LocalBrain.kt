package com.unoone.agent.localbrain

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local LLM brain for UnoOne.
 *
 * This class is a thin wrapper around [GemmaPlanner], which loads a Gemma 4
 * `.litertlm` model via LiteRT-LM and performs manual tool calling.
 *
 * The old ONNX shell has been removed. RuleBasedParser remains the fast offline
 * fallback when no model is loaded.
 */
class LocalBrain {

    private val json = Json { ignoreUnknownKeys = true }
    internal val planner = GemmaPlanner()

    fun isModelLoaded(): Boolean = planner.isLoaded()

    suspend fun loadModel(modelPath: String): Result<Unit> = planner.load(modelPath)

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
