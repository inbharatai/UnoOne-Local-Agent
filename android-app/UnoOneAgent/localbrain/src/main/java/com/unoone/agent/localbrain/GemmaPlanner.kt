package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LiteRT-LM wrapper for Gemma 4 E2B/E4B.
 *
 * - Loads a `.litertlm` model once and keeps a reusable [Conversation].
 * - Registers [UnoOneToolSet] so Gemma can plan phone actions.
 * - Uses **manual** tool calling: the model emits tool calls, but the app executes them.
 */
class GemmaPlanner {

    private val json = Json { ignoreUnknownKeys = true }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile
    private var isLoaded = false

    /** Which backend the model actually loaded on: "GPU", "CPU", or "" if not loaded. */
    @Volatile
    private var activeBackend: String = ""

    /** Last load error message (empty on success). Surfaces device-compatibility status to the UI. */
    @Volatile
    private var lastLoadError: String = ""

    /** Serializes loads so two concurrent callers can't both close + reinitialize (leaking an engine). */
    private val loadMutex = Mutex()

    fun isLoaded(): Boolean = isLoaded

    fun activeBackend(): String = activeBackend

    fun lastLoadError(): String = lastLoadError

    /**
     * Loads the model and initializes a conversation with UnoOne tools.
     * Tries the GPU backend first; if it fails on this device, falls back to CPU so the brain
     * still loads instead of hard-failing. Must be called from a coroutine (initialization is slow).
     */
    suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        // Guard against concurrent loads: two callers could both pass the isLoaded check, both
        // close the existing engine, and both initialize — leaking one native engine.
        loadMutex.withLock {
            if (isLoaded) {
                close()
            }
            lastLoadError = ""
            try {
                Logger.i("GemmaPlanner: loading LiteRT-LM model from $modelPath")
                val (newEngine, backend) = tryLoadBackend(modelPath, Backend.GPU())
                    ?: tryLoadBackend(modelPath, Backend.CPU())
                    ?: run {
                        lastLoadError = "Model failed to load on both GPU and CPU backends"
                        return@withLock Result.Error(lastLoadError)
                    }

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(PromptBuilder.buildSystemInstruction()),
                    tools = listOf(tool(UnoOneToolSet())),
                    automaticToolCalling = false
                )

                // createConversation can throw on some devices; if it does, close the engine we
                // just built (it isn't assigned to the `engine` field yet, so close() later wouldn't
                // release it — that would leak the native LiteRT engine + GPU delegate).
                val newConversation = try {
                    newEngine.createConversation(conversationConfig)
                } catch (e: Exception) {
                    runCatching { newEngine.close() }
                    throw e
                }

                engine = newEngine
                conversation = newConversation
                activeBackend = backend
                isLoaded = true
                Logger.i("GemmaPlanner: model loaded on $backend backend, conversation ready")
                Result.Success(Unit)
            } catch (e: Exception) {
                Logger.e("GemmaPlanner: failed to load model", e)
                isLoaded = false
                activeBackend = ""
                lastLoadError = e.message ?: "Unknown load error"
                Result.Error("Failed to load Gemma model: ${e.message}", e)
            }
        }
    }

    /**
     * Attempts to construct + initialize an [Engine] with the given backend.
     * Returns (engine, backendName) on success, or null on failure (so the caller can try the
     * next backend). Any partially-created engine is released to avoid resource leaks.
     */
    private fun tryLoadBackend(modelPath: String, backend: Backend): Pair<Engine, String>? {
        var candidate: Engine? = null
        return try {
            candidate = Engine(EngineConfig(modelPath = modelPath, backend = backend))
            candidate.initialize()
            val name = when (backend) {
                is Backend.GPU -> "GPU"
                is Backend.CPU -> "CPU"
                is Backend.NPU -> "NPU"
            }
            candidate to name
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: $backend backend failed (${e.message}); will try fallback")
            try {
                candidate?.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * Plans a single tool call from user command + context snapshot.
     * Returns null when the model produces no actionable tool call.
     */
    suspend fun plan(command: String, context: ContextSnapshot): Result<ToolCall> {
        val conv = conversation ?: return Result.Error("Gemma model not loaded")

        return try {
            Logger.d("GemmaPlanner: planning for: $command")
            val userMessage = PromptBuilder.buildUserMessage(command, context)
            val responseMessage = conv.sendMessage(userMessage)

            val toolCalls = responseMessage.toolCalls
            if (toolCalls.isNotEmpty()) {
                val first = toolCalls.first()
                val args = argumentsToJsonObject(first.arguments)
                Logger.d("GemmaPlanner: tool=${first.name}, args=$args")
                Result.Success(ToolCall(first.name, args))
            } else {
                // No tool selected — surface the model text as a speak_response so the user hears it.
                val text = extractText(responseMessage) ?: "I'm not sure how to do that yet."
                Logger.d("GemmaPlanner: no tool call; speaking: $text")
                Result.Success(ToolCall("speak_response", JsonObject(mapOf("text" to JsonPrimitive(text)))))
            }
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: inference failed", e)
            Result.Error("Gemma inference failed: ${e.message}", e)
        }
    }

    /**
     * Releases the model and conversation. Safe to call multiple times.
     */
    fun close() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing conversation: ${e.message}")
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing engine: ${e.message}")
        }
        conversation = null
        engine = null
        isLoaded = false
    }

    private fun extractText(message: Message): String? {
        return message.contents?.contents?.firstNotNullOfOrNull { content ->
            (content as? Content.Text)?.text
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun argumentsToJsonObject(arguments: Map<String, Any?>?): JsonObject {
        if (arguments == null) return JsonObject(emptyMap())
        return JsonObject(arguments.mapValues { (_, value) -> valueToJsonElement(value) })
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun valueToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            null -> JsonPrimitive(null)
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { valueToJsonElement(it) })
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any?>
                argumentsToJsonObject(map)
            }
            else -> JsonPrimitive(value.toString())
        }
    }

}
