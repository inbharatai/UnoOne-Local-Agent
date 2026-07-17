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
import com.unoone.agent.core.model.BackendPreference
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.ModelFamily
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.agent.ResponseTextJoiner
import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.model.ToolParamType
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LiteRT-LM wrapper for UnoOne's on-device Gemma brain.
 *
 * Model-profile aware: [load] takes a [BrainModelSpec] so it can build the correct
 * family-specific system instruction ([PromptBuilder.buildSystemInstruction]) and try the
 * Gemma 4 E2B profile's preferred backend order. No secondary or legacy brain is accepted by
 * this runtime contract.
 *
 * - Loads a `.litertlm` model once and keeps a reusable [Conversation].
 * - Registers [UnoOneToolSet] so Gemma can plan phone actions.
 * - Uses **manual** tool calling (`automaticToolCalling = false`): the model only *proposes* tool
 *   calls; the app executes them after safety checks. The planner additionally **rejects** any
 *   proposed tool whose name is not in [CanonicalToolRegistry] and **validates** required arguments
 *   against the canonical schema before forwarding the call — defense in depth on top of
 *   [com.unoone.agent.safety.SafetyGuard].
 * - Caps one tool call per turn (the compound-step architecture, not the model, handles multi-step).
 * - Surfaces the actual loaded backend + last load error + loaded profile to the UI/diagnostics.
 */
class GemmaPlanner {

    private val json = Json { ignoreUnknownKeys = true }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    /**
     * A separate conversation used only by [judgeSafety]. It is created from the same engine (so it
     * shares model weights — only the KV-cache differs) but has no tools and a classifier system
     * instruction, so a safety judgment never pollutes the planning conversation's multi-turn ReAct
     * context. Null when the brain is not loaded or if the device could not create a second
     * conversation (best-effort: planning still works without it).
     */
    private var judgeConversation: Conversation? = null
    /**
     * A third conversation, used only by [chat] — the CHAT lane. Like [judgeConversation] it is
     * created from the same engine (shared weights, own KV-cache) but carries a tool-less,
     * conversational system instruction ([PromptBuilder.buildChatSystemInstruction]). It never
     * pollutes the planning conversation's ReAct context, and a conversational answer never reaches
     * the safety/confirm gates (a tool-less answer has nothing to gate). Null when the brain is not
     * loaded or the device could not create a third conversation (best-effort: the chat lane then
     * falls back to the agent pipeline).
     */
    private var chatConversation: Conversation? = null
    @Volatile
    private var isLoaded = false

    /** Which backend the model actually loaded on: "GPU", "CPU", or "" if not loaded. */
    @Volatile
    private var activeBackend: String = ""

    /** Last load error message (empty on success). Surfaces device-compatibility status to the UI. */
    @Volatile
    private var lastLoadError: String = ""

    /** The profile currently loaded into the engine, or null when nothing is loaded. */
    @Volatile
    private var loadedSpec: BrainModelSpec? = null

    /** Serializes loads so two concurrent callers can't both close + reinitialize (leaking an engine). */
    private val loadMutex = Mutex()

    /**
     * Serializes inference. LiteRT-LM `Conversation.sendMessage` is not guaranteed thread-safe, and
     * UnoOne now has concurrent planners: the live command path ([com.unoone.agent.AgentOrchestrator]
     * via [com.unoone.agent.parsing.CommandParser]) and the read-only self-test probe
     * ([com.unoone.agent.AgentOrchestrator.planToolCall] via [com.unoone.agent.brain.BrainSelfTest]).
     * Without this, a background voice command and a Settings self-test could call `sendMessage` on
     * the same conversation from two threads. Callers wait their turn; each is still bounded by its
     * own [INFERENCE_TIMEOUT_MS], so a stuck call releases the lock within 30s (then closes the brain).
     */
    private val inferenceMutex = Mutex()

    fun isLoaded(): Boolean = isLoaded

    fun activeBackend(): String = activeBackend

    fun lastLoadError(): String = lastLoadError

    /** The currently loaded brain profile, or null. */
    fun loadedProfile(): BrainModelSpec? = loadedSpec

    /**
     * Convenience single-arg load — loads [modelPath] with the sole Gemma 4 E2B profile. Callers
     * that already hold the model specification should use the explicit overload below.
     */
    suspend fun load(modelPath: String): Result<Unit> =
        load(modelPath, com.unoone.agent.core.model.BrainModelRegistry.defaultProfile)

    /**
     * Loads [modelPath] as [spec] and initializes a conversation with UnoOne tools.
     * Tries the profile's preferred hardware backend first; if it fails on this device, falls back
     * to CPU so the brain still loads instead of hard-failing. Must be called from a coroutine
     * (initialization is slow). Never leaves [isLoaded] true after a partial failure.
     */
    suspend fun load(modelPath: String, spec: BrainModelSpec): Result<Unit> = withContext(Dispatchers.IO) {
        val loadStart = System.currentTimeMillis()
        // Guard against concurrent loads / model replacement: two callers could both pass the
        // isLoaded check, both close the existing engine, and both initialize — leaking one engine.
        // Also prevents simultaneous inference and model replacement.
        loadMutex.withLock {
            if (isLoaded) {
                close()
            }
            lastLoadError = ""
            try {
                Logger.i("GemmaPlanner: loading ${spec.displayName} (${spec.manifestId}) from $modelPath")
                val backends = backendsToTry(spec.preferredBackend)
                val (newEngine, backend) = tryLoadBackends(modelPath, backends)
                    ?: run {
                        lastLoadError = "${spec.displayName} failed to load on any backend (${backendsNames(backends)})"
                        return@withLock Result.Error(lastLoadError)
                    }

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(PromptBuilder.buildSystemInstruction(spec.modelFamily)),
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
                // Best-effort second conversation for the safety judge. Shares the engine (model
                // weights) but has no tools and a classifier system prompt, so judging an action
                // never pollutes the planning conversation's ReAct context. If the device cannot
                // create a second conversation, planning still works — the judge is simply disabled.
                judgeConversation = try {
                    newEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SAFETY_JUDGE_SYSTEM_INSTRUCTION),
                            tools = emptyList(),
                            automaticToolCalling = false
                        )
                    )
                } catch (e: Exception) {
                    Logger.w("GemmaPlanner: safety-judge conversation unavailable (${e.message}); judge disabled")
                    null
                }
                // Best-effort third conversation for the CHAT lane — same engine, tool-less
                // conversational system prompt. If the device cannot create a third conversation,
                // chat falls back to the agent pipeline (planning a speak_response). Mirrors the
                // judge-conversation discipline above.
                chatConversation = try {
                    newEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(PromptBuilder.buildChatSystemInstruction()),
                            tools = emptyList(),
                            automaticToolCalling = false
                        )
                    )
                } catch (e: Exception) {
                    Logger.w("GemmaPlanner: chat conversation unavailable (${e.message}); chat falls back to agent path")
                    null
                }
                activeBackend = backend
                loadedSpec = spec
                isLoaded = true
                Logger.i("GemmaPlanner: ${spec.displayName} loaded on $backend backend, conversation ready")
                com.unoone.agent.observability.Diagnostics.recordModelLoadTime(System.currentTimeMillis() - loadStart)
                Result.Success(Unit)
            } catch (e: Exception) {
                Logger.e("GemmaPlanner: failed to load ${spec.displayName}", e)
                // Full cleanup on any partial failure — never leave isLoaded true with no engine.
                runCatching { close() }
                isLoaded = false
                activeBackend = ""
                loadedSpec = null
                lastLoadError = e.message ?: "Unknown load error"
                Result.Error("Failed to load ${spec.displayName}: ${e.message}", e)
            }
        }
    }

    /** Maps a profile's [BackendPreference] to the ordered LiteRT-LM backends to attempt. */
    private fun backendsToTry(pref: BackendPreference): List<Backend> = when (pref) {
        BackendPreference.CPU_ONLY -> listOf(Backend.CPU())
        BackendPreference.GPU_FIRST, BackendPreference.ANY -> listOf(Backend.GPU(), Backend.CPU())
    }

    private fun backendsNames(backends: List<Backend>): String =
        backends.joinToString("→") { backendName(it) }

    private fun backendName(backend: Backend): String = when (backend) {
        is Backend.GPU -> "GPU"
        is Backend.CPU -> "CPU"
        is Backend.NPU -> "NPU"
    }

    /**
     * Tries each backend in order; returns (engine, backendName) for the first that constructs +
     * initializes, or null if all fail. Any partially-created engine is released to avoid leaks.
     */
    private fun tryLoadBackends(modelPath: String, backends: List<Backend>): Pair<Engine, String>? {
        for (backend in backends) {
            val result = tryLoadBackend(modelPath, backend)
            if (result != null) return result
        }
        return null
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
            candidate to backendName(backend)
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: ${backendName(backend)} backend failed (${e.message}); will try fallback")
            try {
                candidate?.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * Plans a single tool call from user command + context snapshot.
     * Returns [Result.Success] with a validated, canonical [ToolCall], or [Result.Error] when the
     * model is not loaded, times out, or proposes an unknown/malformed tool call (which is rejected
     * — never executed). Returns null never: a no-tool answer becomes a `speak_response` ToolCall.
     */
    suspend fun plan(command: String, context: ContextSnapshot): Result<ToolCall> {
        val conv = conversation ?: return Result.Error("Gemma model not loaded")

        return try {
            Logger.d("GemmaPlanner: planning for: $command")
            val userMessage = PromptBuilder.buildUserMessage(command, context, ContextBudget.forCommand(command))

            // Inference is serialized by [inferenceMutex] (LiteRT-LM Conversation is not guaranteed
            // thread-safe, and the live command path and the read-only self-test probe can both run
            // inference concurrently). Then a timeout bounds observable latency: Kotlin coroutine
            // cancellation cannot interrupt a blocking JNI call, so on timeout we abandon the result
            // and close the engine (its conversation may be in an indeterminate state) so the next
            // load rebuilds cleanly. The native call may run to completion on the IO dispatcher.
            val responseMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { conv.sendMessage(userMessage) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: inference timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma inference timed out")
            }

            extractValidatedToolCall(responseMessage)
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: inference failed", e)
            Result.Error("Gemma inference failed: ${e.message}", e)
        }
    }

    /**
     * Streaming first-turn plan: same canonical-tool contract as [plan] (one validated [ToolCall]
     * per turn, unknown tools rejected, required args validated, `speak_response` fallback), but
     * uses LiteRT-LM's `Conversation.sendMessageAsync(Message, Map)` which returns a cold
     * `Flow<Message>` that emits the model's partial output as it is generated.
     *
     * [onDelta] is invoked with each incremental text delta (the new suffix since the previous
     * emission) so the caller can surface partial reasoning/spoken text to the UI timeline as it
     * streams. The final validated [ToolCall] is returned exactly as in [plan].
     *
     * Honesty note (device-time-only): the `litertlm-android` bytecode is newer than the JDK 17 test
     * JVM can load, so this path is NOT JVM-unit-tested — it is verified by the device matrix, like
     * [planNext] / [judgeSafety]. The pure delta-reduction that decides which text to surface per
     * emission is JVM-tested in [com.unoone.agent.core.agent.StreamingTextReducer]. Whether the
     * `Flow` emits cumulative or delta `Message`s, and whether the final emission carries the full
     * `toolCalls`, is settled on-device; the reducer handles both semantics and the final message is
     * validated through the same [extractValidatedToolCall] as the synchronous path.
     *
     * The same inference [Mutex] + [INFERENCE_TIMEOUT_MS] bound applies: the cold flow is collected
     * inside the lock + timeout, so a stuck stream releases the lock within 30s (then closes the
     * brain). On timeout the brain is closed and an Error is returned, matching [plan].
     */
    suspend fun planStreaming(
        command: String,
        context: ContextSnapshot,
        onDelta: (String) -> Unit
    ): Result<ToolCall> {
        val conv = conversation ?: return Result.Error("Gemma model not loaded")

        return try {
            Logger.d("GemmaPlanner: streaming plan for: $command")
            val userMessage = PromptBuilder.buildUserMessage(command, context, ContextBudget.forCommand(command))
            val reducer = com.unoone.agent.core.agent.StreamingTextReducer()

            // Collect the cold Flow inside the inference lock + timeout, mirroring [plan]. Each emitted
            // Message is a (cumulative) snapshot: feed its text to the reducer and forward the delta.
            // The last emission is the complete message, validated identically to the sync path.
            val finalMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            val flow = conv.sendMessageAsync(userMessage)
                            var last: Message? = null
                            flow.collect { partial ->
                                last = partial
                                val text = extractText(partial) ?: ""
                                val delta = reducer.onSnapshot(text)
                                if (delta.isNotEmpty()) onDelta(delta)
                            }
                            last
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: streaming inference timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma inference timed out")
            }

            finalMessage?.let { extractValidatedToolCall(it) }
                ?: Result.Error("Gemma streaming produced no response")
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: streaming inference failed", e)
            Result.Error("Gemma streaming inference failed: ${e.message}", e)
        }
    }

    /**
     * Multimodal vision path for `describe_scene`: sends a screenshot ([imageBytes]) plus a text
     * prompt to the loaded conversation using LiteRT-LM's multimodal API
     * (`Message.user(Contents.of(Content.Text(prompt), Content.ImageBytes(bytes)))`), and returns the
     * model's free-text scene description.
     *
     * **Honesty (INACTIVE with the shipped model):** the loaded Gemma 4 E2B `.litertlm`
     * artifact is text-only — they have no vision weights — so this method either
     * errors or ignores the image. The orchestrator does NOT call it unless a vision-capable model
     * is loaded (gated by `VISION_MODEL_ENABLED`); when it is called and fails, the caller falls
     * back to the always-available OCR + context description
     * ([com.unoone.agent.core.agent.SceneDescriptionBuilder]). Wiring this against the real AAR
     * (`Content.ImageBytes` + `EngineConfig.visionBackend`/`maxNumImages`) means a future
     * vision-capable `.litertlm` artifact lights the path up without code changes here.
     *
     * Device-time-only (not JVM-testable — litertlm bytecode newer than JDK 17). The same inference
     * [Mutex] + [INFERENCE_TIMEOUT_MS] bound + close-on-timeout discipline as [plan] applies.
     */
    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> {
        val conv = conversation ?: return Result.Error("Gemma model not loaded")
        return try {
            val prompt = if (aspect.isBlank()) {
                "Describe what is on the phone screen in one or two short sentences for a user who cannot see it."
            } else {
                "Describe what is on the phone screen in one or two short sentences, focusing on: $aspect"
            }
            val userMessage = Message.user(Contents.of(Content.Text(prompt), Content.ImageBytes(imageBytes)))
            val responseMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { conv.sendMessage(userMessage) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: vision inference timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma vision timed out")
            }
            val text = extractText(responseMessage) ?: ""
            if (text.isBlank()) Result.Error("Gemma vision produced no description")
            else Result.Success(text)
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: vision path unavailable (${e.message})")
            Result.Error("Gemma vision unavailable: ${e.message}")
        }
    }

    /**
     * Continues the live conversation after a tool executes: feeds the tool's result back to the
     * model as a `Role.TOOL` message carrying a `Content.ToolResponse`, then returns the model's
     * next proposed — and validated — [ToolCall]. This is the "Observe" half of the ReAct loop.
     *
     * LiteRT-LM detail (verified from the 0.13.1 AAR bytecode): the `String` and `Contents`
     * overloads of `Conversation.sendMessage` force `Role.USER`, so they cannot carry a tool
     * result. Only the `Message` overload preserves the message's own role; `Message.tool(contents)`
     * sets `Role.TOOL`, which the engine treats as a tool-response turn and continues reasoning from.
     * We reuse the same inference Mutex + timeout + unknown-tool rejection + arg validation as
     * [plan], so every continuation is held to the same canonical-tool contract as the first call.
     *
     * [observation] is truncated before being sent to bound the on-device context window. Device-
     * time only: `litertlm-android` bytecode is newer than the JDK 17 test JVM can load, so this
     * multi-turn path is verified by the Brain Self-Test / device matrix, not by a JVM unit test.
     */
    suspend fun planNext(prevTool: String, observation: String): Result<ToolCall> {
        val conv = conversation ?: return Result.Error("Gemma model not loaded")
        return try {
            val trimmed = observation.take(MAX_OBSERVATION_CHARS)
            Logger.d("GemmaPlanner: planNext after '$prevTool', observation=${trimmed.length} chars")
            val toolMessage = Message.tool(Contents.of(Content.ToolResponse(prevTool, mapOf("result" to trimmed))))
            val responseMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { conv.sendMessage(toolMessage) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: planNext timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma inference timed out")
            }
            extractValidatedToolCall(responseMessage)
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: planNext inference failed", e)
            Result.Error("Gemma inference failed: ${e.message}", e)
        }
    }

    /**
     * A second on-device inference that reviews a proposed action for safety, catching paraphrased
     * harm the keyword-based [com.unoone.agent.safetyguard.SafetyGuard] can miss (e.g. "wipe
     * everything" → `delete_all_notes`, or a payment disguised in the user's wording). Uses a
     * dedicated judge conversation (no tools, classifier system prompt) so it never pollutes the
     * planning/ReAct conversation.
     *
     * Returns a [SafetyVerdict]. The orchestrator merges it via [SafetyJudgePolicy.escalate], which
     * **only escalates** — the judge can never weaken the keyword tier. If the brain is not loaded,
     * the judge conversation is unavailable, or inference fails, the caller keeps the keyword
     * result unchanged (fail-safe). Device-time verified: like [planNext], this cannot be JVM-tested
     * because of the litertlm-android bytecode/JDK-17 constraint.
     */
    suspend fun judgeSafety(
        toolName: String,
        argsJson: String,
        inputText: String
    ): Result<SafetyVerdict> {
        val conv = judgeConversation ?: return Result.Error("Safety judge not available")
        return try {
            val prompt = buildSafetyJudgePrompt(toolName, argsJson, inputText)
            val responseMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { conv.sendMessage(prompt) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: judgeSafety timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma judge timed out")
            }
            val text = extractText(responseMessage) ?: ""
            Result.Success(parseVerdict(text))
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: judgeSafety failed", e)
            Result.Error("Gemma judge failed: ${e.message}", e)
        }
    }

    /**
     * One-shot conversational answer on the dedicated tool-less [chatConversation] — the CHAT lane.
     * Mirrors [judgeSafety]'s conversation discipline (same inference [Mutex] + [INFERENCE_TIMEOUT_MS]
     * bound + close-on-timeout). Returns the model's free-text reply with **all** text fragments
     * joined (via [ResponseTextJoiner], so multi-fragment answers are not truncated to the first).
     *
     * The orchestrator only calls this for question-shaped, action-free input
     * ([com.unoone.agent.core.agent.IntentClassifier] CHAT), so a chat answer never reaches the
     * safety/confirm gates (a tool-less answer has nothing to gate) or the ReAct loop, and never
     * builds a screen/OCR context snapshot. If the brain is not loaded, the chat conversation is
     * unavailable, inference times out, or the answer is blank, the caller falls back to the agent
     * pipeline (fail-safe). Device-time verified (litertlm bytecode > JDK 17 test JVM).
     */
    suspend fun chat(command: String, responseLanguage: String = ""): Result<String> {
        val conv = chatConversation ?: return Result.Error("Chat conversation not available")
        return try {
            val prompt = PromptBuilder.buildChatUserMessage(command, responseLanguage)
            val responseMessage = try {
                inferenceMutex.withLock {
                    withTimeout(INFERENCE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { conv.sendMessage(prompt) }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Logger.w("GemmaPlanner: chat timed out after ${INFERENCE_TIMEOUT_MS}ms; closing brain")
                runCatching { close() }
                return Result.Error("Gemma chat timed out")
            }
            val text = extractText(responseMessage) ?: ""
            if (text.isBlank()) Result.Error("Gemma chat produced no answer")
            else Result.Success(text)
        } catch (e: Exception) {
            Logger.e("GemmaPlanner: chat failed", e)
            Result.Error("Gemma chat failed: ${e.message}", e)
        }
    }

    /** Builds the action description sent to the judge conversation. [inputText] is truncated. */
    private fun buildSafetyJudgePrompt(toolName: String, argsJson: String, inputText: String): String =
        buildString {
            appendLine("Proposed phone action:")
            appendLine("tool: $toolName")
            appendLine("arguments: ${argsJson.take(MAX_OBSERVATION_CHARS)}")
            appendLine("user input: ${inputText.take(500)}")
            append("Verdict (one token: SAFE, NEEDS_CONFIRM, or UNSAFE): ")
        }

    /**
     * Parses the judge's free-text response into a verdict. Order matters: `UNSAFE` is checked
     * before `SAFE` because "UNSAFE" contains the substring "SAFE". Anything unparseable becomes
     * [SafetyVerdict.UNCERTAIN] so the caller fails safe (keeps the keyword tier).
     */
    private fun parseVerdict(text: String): SafetyVerdict {
        val upper = text.uppercase()
        return when {
            upper.contains("UNSAFE") -> SafetyVerdict.UNSAFE
            upper.contains("NEEDS_CONFIRM") || upper.contains("NEEDS CONFIRM") -> SafetyVerdict.NEEDS_CONFIRM
            upper.contains("SAFE") -> SafetyVerdict.SAFE
            else -> {
                Logger.w("GemmaPlanner: judge verdict unparseable: ${text.take(80)}")
                SafetyVerdict.UNCERTAIN
            }
        }
    }

    /**
     * Turns a model [Message] into a validated [ToolCall]. Shared by [plan] (first turn) and
     * [planNext] (ReAct continuations) so the canonical-tool contract — reject unknown names,
     * validate required args, cap one call per turn, fall back to `speak_response` when the model
     * emits no tool — is applied identically to every model proposal, first or follow-up.
     */
    private fun extractValidatedToolCall(responseMessage: Message): Result<ToolCall> {
        val toolCalls = responseMessage.toolCalls
        if (toolCalls.isNotEmpty()) {
            // Cap one tool call per turn — the compound-step architecture (RuleBasedParser) and the
            // ReAct loop (one continuation at a time) handle multi-step, not the model emitting a
            // batch. Take the first; log if the model emitted more.
            if (toolCalls.size > 1) {
                Logger.w("GemmaPlanner: model emitted ${toolCalls.size} tool calls; taking only the first")
            }
            val first = toolCalls.first()
            val name = first.name
            // Reject unknown tools before any execution path sees them.
            if (!CanonicalToolRegistry.isKnown(name)) {
                Logger.w("GemmaPlanner: rejecting unknown tool '$name'")
                return Result.Error("Rejected unknown tool: $name")
            }
            val args = argumentsToJsonObject(first.arguments)
            // Validate arguments against the canonical schema before forwarding.
            val validation = validateArgs(name, first.arguments)
            if (validation != null) {
                Logger.w("GemmaPlanner: rejecting malformed args for '$name': $validation")
                return Result.Error("Rejected malformed arguments for $name: $validation")
            }
            Logger.d("GemmaPlanner: tool=$name, args=$args")
            return Result.Success(ToolCall(name, args))
        }
        // No tool selected — surface the model text as a speak_response so the user hears it.
        val text = extractText(responseMessage) ?: "I'm not sure how to do that yet."
        Logger.d("GemmaPlanner: no tool call; speaking: $text")
        return Result.Success(ToolCall("speak_response", JsonObject(mapOf("text" to JsonPrimitive(text)))))
    }

    /**
     * Validates a model-proposed argument map against the canonical schema for [toolName].
     * Returns null if valid, or a short reason string if a required argument is missing or a value's
     * runtime type is incompatible with the declared type. Optional arguments may be absent/null.
     */
    private fun validateArgs(toolName: String, arguments: Map<String, Any?>?): String? {
        val schema = CanonicalToolRegistry.schemaFor(toolName) ?: return "unknown tool"
        val args = arguments ?: emptyMap()
        for (param in schema.params) {
            val present = args.containsKey(param.name) && args[param.name] != null
            if (param.required && !present) {
                return "missing required argument '${param.name}'"
            }
            if (!present) continue // optional and absent — fine
            if (!typeMatches(param.type, args[param.name])) {
                return "argument '${param.name}' has wrong type (expected ${param.type})"
            }
        }
        return null
    }

    private fun typeMatches(expected: ToolParamType, value: Any?): Boolean = when (expected) {
        ToolParamType.STRING -> value is String
        ToolParamType.INT -> value is Int || (value is Number && value.toDouble() == value.toDouble().toInt().toDouble())
        ToolParamType.BOOLEAN -> value is Boolean
        ToolParamType.FLOAT, ToolParamType.DOUBLE -> value is Number
        ToolParamType.STRING_LIST -> value is List<*>
    }

    /**
     * Releases the model and conversation. Safe to call multiple times. Clears [loadedSpec].
     */
    fun close() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing conversation: ${e.message}")
        }
        try {
            judgeConversation?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing judge conversation: ${e.message}")
        }
        try {
            chatConversation?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing chat conversation: ${e.message}")
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: error closing engine: ${e.message}")
        }
        conversation = null
        judgeConversation = null
        chatConversation = null
        engine = null
        isLoaded = false
        loadedSpec = null
        activeBackend = ""
    }

    private fun extractText(message: Message): String? =
        // Concatenate ALL text fragments, not just the first — LiteRT-LM may split a response
        // across multiple Content.Text parts; keeping only the first collapsed multi-part answers
        // to a single fragment (the `?`/truncated final-card bug). Null when no non-blank text.
        ResponseTextJoiner.join(
            message.contents?.contents?.map { (it as? Content.Text)?.text } ?: emptyList()
        )

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

    companion object {
        /** Per-inference timeout. Generous for first-token on mobile, but bounded. */
        const val INFERENCE_TIMEOUT_MS: Long = 30_000L

        /**
         * Max characters of a tool result fed back into the conversation by [planNext]. Tool output
         * (a screen read, a notes search) can be large; truncating bounds the on-device context
         * window and per-step latency while leaving plenty for the model to reason over.
         */
        const val MAX_OBSERVATION_CHARS: Int = 1_500

        /**
         * System instruction for the dedicated safety-judge conversation. The judge reviews a
         * proposed action and the raw user input and emits one verdict token. It is explicitly told
         * to flag disguised/destructive/irreversible/sensitive actions (payments, passwords, OTPs,
         * account removal, app installs, silent control) — the categories the keyword filter must
         * never let through, re-stated so the judge can catch them even when paraphrased.
         */
        private const val SAFETY_JUDGE_SYSTEM_INSTRUCTION: String =
            "You are UnoOne's on-device safety judge. You review a proposed phone action and the " +
                "user's raw wording, then decide if it is safe. Respond with exactly one token and " +
                "nothing else: SAFE, NEEDS_CONFIRM, or UNSAFE.\n" +
                "- SAFE: a routine, reversible action with no real risk (open an app, read the " +
                "screen, create or search a note, check the calendar).\n" +
                "- NEEDS_CONFIRM: a moderate-risk action the user should confirm first (send a " +
                "WhatsApp, open a URL, add a calendar event, start a voice recording, share text).\n" +
                "- UNSAFE: an irreversible, destructive, or sensitive action, or any wording that " +
                "looks like an attempt to disguise one. Always UNSAFE for: deleting data or " +
                "\"wiping\"/\"erasing\" notes, payments or money transfers, passwords or OTPs, " +
                "account removal, installing or uninstalling apps, and silent/hidden control of the " +
                "device. When unsure, choose the stricter verdict."
    }

}
