package com.unoone.agent.localbrain

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LocalBrain {

    private val json = Json { ignoreUnknownKeys = true }
    private var isLoaded = false

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun isModelLoaded(): Boolean = isLoaded

    fun loadModel(modelPath: String): Result<Unit> {
        return try {
            Logger.i("Loading model from $modelPath via ONNX Runtime")
            env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()

            // Try NNAPI hardware acceleration — works on most modern Android devices
            // with dedicated NPUs (Snapdragon, Exynos, Dimensity, Tensor).
            // Gracefully falls back to CPU if NNAPI is unavailable or fails.
            try {
                options.addNnapi()
                Logger.i("NNAPI hardware acceleration enabled")
            } catch (e: Exception) {
                Logger.w("NNAPI not available on this device, using CPU inference: ${e.message}")
            }

            session = env?.createSession(modelPath, options)
            isLoaded = true
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("Failed to load model", e)
            Result.Error("Model load failed: ${e.message}", e)
        }
    }

    fun unloadModel() {
        Logger.i("Unloading local LLM")
        session?.close()
        env?.close()
        session = null
        env = null
        isLoaded = false
    }

    fun runInference(prompt: String): Result<ToolCall> {
        if (!isLoaded || session == null || env == null) {
            return Result.Error("Local model not loaded")
        }

        return try {
            Logger.d("Running inference for: $prompt")

            // Pro-level implementation:
            // 1. Tokenize (requires a separate tokenizer module or native implementation)
            // 2. Run session
            // 3. De-tokenize
            //
            // For now, since tokenizer is complex to implement from scratch in Kotlin without libraries,
            // we keep the placeholder but structure it for the actual ONNX session.
            // RuleBasedParser handles all commands until a real tokenizer + KV-cache is integrated.

            val mockJsonOutput = "{\"tool\": \"create_note\", \"args\": {\"title\": \"Gemma Note\", \"content\": \"$prompt\"}}"
            parseToolCall(mockJsonOutput)
        } catch (e: Exception) {
            Logger.e("Inference failed", e)
            Result.Error("Inference failed: ${e.message}")
        }
    }

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