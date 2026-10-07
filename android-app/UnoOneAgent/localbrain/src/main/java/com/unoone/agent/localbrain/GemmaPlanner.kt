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
import com.unoone.agent.core.model.BackendQualificationChoice
import com.unoone.agent.core.model.BrainModelId
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.E4bRuntimeBudgets
import com.unoone.agent.core.model.E4bRuntimeCoordinator
import com.unoone.agent.core.model.E4bRuntimeState
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.agent.ResponseTextJoiner
import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.agent.StreamingTextReducer
import com.unoone.agent.core.model.ToolParamType
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LiteRT-LM wrapper for UnoOne's on-device Gemma brain.
 *
 * Model-profile aware: [load] takes a [BrainModelSpec] so it can build the correct
 * family-specific system instruction ([PromptBuilder.buildSystemInstruction]) and try the
 * Gemma 4 E4B profile's qualified backend choice. No secondary or legacy brain is accepted by
 * this runtime contract.
 *
 * - Loads one `.litertlm` engine; conversations are lazy and bounded to the operation that needs them.
 * - Registers [UnoOneToolSet] so Gemma can plan phone actions.
 * - Uses **manual** tool calling (`automaticToolCalling = false`): the model only *proposes* tool
 *   calls; the app executes them after safety checks. The planner additionally **rejects** any
 *   proposed tool whose name is not in [CanonicalToolRegistry] and **validates** required arguments
 *   against the canonical schema before forwarding the call — defense in depth on top of
 *   [com.unoone.agent.safety.SafetyGuard].
 * - Caps one tool call per turn (the compound-step architecture, not the model, handles multi-step).
 * - Surfaces the actual loaded backend + last load error + loaded profile to the UI/diagnostics.
 */
class GemmaPlanner(
    private var backendChoice: BackendQualificationChoice = BackendQualificationChoice.AUTO
) {

    private val json = Json { ignoreUnknownKeys = true }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    /**
     * A temporary conversation used only by [judgeSafety]. It is created lazily from the same engine
     * and has no tools plus a classifier system
     * instruction, so a safety judgment never pollutes the planning conversation's multi-turn ReAct
     * context. It is closed after the judgment so its KV cache does not remain resident.
     */
    private var judgeConversation: Conversation? = null
    /**
     * A temporary conversation used only by [chat] — the CHAT lane. It carries a tool-less,
     * conversational system instruction ([PromptBuilder.buildChatSystemInstruction]). It never
     * pollutes the planning conversation's ReAct context, and a conversational answer never reaches
     * the safety/confirm gates (a tool-less answer has nothing to gate). It is closed after the turn.
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

    @Volatile private var cleanupUncertain = false
    @Volatile private var nativeQuarantined = false
    private val retainedConversations = mutableListOf<Conversation>()

    private val nativeInference = LiteRtCancellableInference("GemmaPlanner")

    fun isLoaded(): Boolean = isLoaded

    fun activeBackend(): String = activeBackend

    fun lastLoadError(): String = lastLoadError

    /** The currently loaded brain profile, or null. */
    fun loadedProfile(): BrainModelSpec? = loadedSpec

    fun setBackendQualificationChoice(choice: BackendQualificationChoice) {
        check(!isLoaded) { "Unload E4B before changing backend qualification mode" }
        backendChoice = choice
    }

    /**
     * Convenience single-arg load — loads [modelPath] with the sole Gemma 4 E4B profile. Callers
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
    suspend fun load(modelPath: String, spec: BrainModelSpec, ownerToken: String = E4bRuntimeCoordinator.PHONE_OWNER): Result<Unit> = withContext(Dispatchers.IO) {
        val loadStart = System.currentTimeMillis()
        // Guard against concurrent loads / model replacement: two callers could both pass the
        // isLoaded check, both close the existing engine, and both initialize — leaking one engine.
        // Also prevents simultaneous inference and model replacement.
        E4bRuntimeCoordinator.operationMutex.withLock {
            if (!E4bRuntimeCoordinator.canReplaceAllocation(this@GemmaPlanner, ownerToken)) {
                return@withLock Result.Error("Native allocation owned or reserved by another runtime")
            }
            E4bRuntimeCoordinator.transition(E4bRuntimeState.LOADING_PHONE, PHONE_OWNER, spec.manifestId)
            check(nativeInference.awaitNativeIdle()) { "Native inference still active; load refused" }
            if (!closeInternal()) return@withLock Result.Error(lastLoadError)
            if (!E4bRuntimeCoordinator.claimAllocation(this@GemmaPlanner, ownerToken, PHONE_OWNER)) {
                return@withLock Result.Error("Native allocation owned or reserved by another runtime")
            }
            lastLoadError = ""
            try {
                Logger.i("GemmaPlanner: loading ${spec.displayName} (${spec.manifestId}) from $modelPath")
                val backends = backendsToTry(spec.preferredBackend)
                val (newEngine, backend) = tryLoadBackends(modelPath, backends, spec)
                    ?: run {
                        E4bRuntimeCoordinator.acknowledgeClosed(this@GemmaPlanner)
                        lastLoadError = "${spec.displayName} failed to load on any backend (${backendsNames(backends)})"
                        return@withLock Result.Error(lastLoadError)
                    }

                engine = newEngine
                conversation = null
                judgeConversation = null
                chatConversation = null
                activeBackend = backend
                loadedSpec = spec
                isLoaded = true
                val budget = E4bRuntimeBudgets.phone(spec)
                Logger.i("GemmaPlanner: ${spec.displayName} loaded on $backend; context=${budget.contextTokens}, output=${budget.outputTokens}")
                E4bRuntimeCoordinator.transition(E4bRuntimeState.PHONE_READY, PHONE_OWNER, backend)
                com.unoone.agent.observability.Diagnostics.recordModelLoadTime(System.currentTimeMillis() - loadStart)
                Result.Success(Unit)
            } catch (e: Exception) {
                Logger.e("GemmaPlanner: failed to load ${spec.displayName}", e)
                // Full cleanup on any partial failure — never leave isLoaded true with no engine.
                closeInternal()
                lastLoadError = e.message ?: "Unknown load error"
                E4bRuntimeCoordinator.transition(E4bRuntimeState.FAILED, PHONE_OWNER, lastLoadError)
                Result.Error("Failed to load ${spec.displayName}: ${e.message}", e)
            }
        }
    }

    /** Maps a profile's [BackendPreference] to the ordered LiteRT-LM backends to attempt. */
    private fun backendsToTry(pref: BackendPreference): List<Backend> = when (pref) {
        BackendPreference.CPU_ONLY -> listOf(Backend.CPU())
        BackendPreference.GPU_FIRST, BackendPreference.ANY -> when (backendChoice) {
            BackendQualificationChoice.CPU -> listOf(Backend.CPU())
            BackendQualificationChoice.GPU -> listOf(Backend.GPU())
            // No E4B GPU qualification record exists yet. AUTO therefore uses CPU rather than
            // treating successful allocation as proof of accuracy/stability.
            BackendQualificationChoice.AUTO -> listOf(Backend.CPU())
        }
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
    private fun tryLoadBackends(modelPath: String, backends: List<Backend>, spec: BrainModelSpec): Pair<Engine, String>? {
        for (backend in backends) {
            val result = tryLoadBackend(modelPath, backend, spec)
            if (result != null) return result
        }
        return null
    }

    /**
     * Attempts to construct + initialize an [Engine] with the given backend.
     * Returns (engine, backendName) on success, or null on failure (so the caller can try the
     * next backend). Any partially-created engine is released to avoid resource leaks.
     */
    private fun tryLoadBackend(modelPath: String, backend: Backend, spec: BrainModelSpec): Pair<Engine, String>? {
        var candidate: Engine? = null
        return try {
            candidate = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (spec.id == BrainModelId.GEMMA_4_E2B) Backend.CPU() else null,
                    maxNumImages = if (spec.id == BrainModelId.GEMMA_4_E2B) 1 else null,
                    maxNumTokens = E4bRuntimeBudgets.phone(spec).contextTokens
                )
            )
            candidate.initialize()
            candidate to backendName(backend)
        } catch (e: Exception) {
            Logger.w("GemmaPlanner: ${backendName(backend)} backend failed (${e.message}); will try fallback")
            try {
                candidate?.close()
            } catch (cleanup: Exception) {
                cleanupUncertain = true
                nativeQuarantined = true
                E4bRuntimeCoordinator.quarantine(this)
                engine = candidate
                isLoaded = candidate != null // Residency proof, even when initialization failed.
                throw cleanup
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
        if (PlannerToolRouter.speechOnly(command)) {
            return Result.Success(speechFallback(command))
        }
        return runPhoneInference("plan") { activeEngine, spec ->
            resetPlanningConversation()
            val conv = createPlanningConversation(activeEngine, spec, command).also { conversation = it }
            val message = Message.user(
                Contents.of(PromptBuilder.buildUserMessage(command, context, ContextBudget.forCommand(command)))
            )
            extractValidatedToolCall(
                nativeInference.send(
                    conv,
                    message,
                    INFERENCE_TIMEOUT_MS,
                    E4bRuntimeBudgets.phone(spec).outputTokens
                )
            )
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
        if (PlannerToolRouter.speechOnly(command)) {
            return Result.Success(speechFallback(command))
        }
        val reducer = com.unoone.agent.core.agent.StreamingTextReducer()
        return runPhoneInference("streaming plan") { activeEngine, spec ->
            resetPlanningConversation()
            val conv = createPlanningConversation(activeEngine, spec, command).also { conversation = it }
            val message = Message.user(
                Contents.of(PromptBuilder.buildUserMessage(command, context, ContextBudget.forCommand(command)))
            )
            val final = nativeInference.send(
                conv,
                message,
                INFERENCE_TIMEOUT_MS,
                E4bRuntimeBudgets.phone(spec).outputTokens
            ) { partial ->
                val delta = reducer.onSnapshot(extractRawText(partial).orEmpty())
                if (delta.isNotEmpty()) onDelta(delta)
            }
            extractValidatedToolCall(final)
        }
    }

    /**
     * Multimodal vision path for `describe_scene`: sends a screenshot ([imageBytes]) plus a text
     * prompt to the loaded conversation using LiteRT-LM's multimodal API
     * (`Message.user(Contents.of(Content.Text(prompt), Content.ImageBytes(bytes)))`), and returns the
     * model's free-text scene description.
     *
     * E2B initializes the CPU vision executor and accepts actual encoded image bytes.
     * E4B keeps its existing text-only runtime configuration and explicitly rejects this path.
     * Device qualification remains required; no screenshot/OCR fallback is represented as vision.
     *
     * Device-time-only (not JVM-testable — litertlm bytecode newer than JDK 17). The same inference
     * [Mutex] + [INFERENCE_TIMEOUT_MS] bound + close-on-timeout discipline as [plan] applies.
     */
    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> =
        controllerRequest(VISION_SYSTEM_INSTRUCTION,
            "Describe the screen, focusing on: ${aspect.take(500)}", imageBytes)

    /** Isolated tool-less request on the shared engine, never a second engine. */
    internal suspend fun controllerRequest(system: String, prompt: String, image: ByteArray? = null): Result<String> =
        runPhoneInference("device controller") { activeEngine, spec ->
            require(system.length <= 4096 && prompt.length <= 10000) { "Controller context exceeds budget" }
            if (image != null) {
                check(spec.id == BrainModelId.GEMMA_4_E2B) { "Loaded profile has no enabled vision runtime" }
                require(image.isNotEmpty() && image.size <= 8 * 1024 * 1024) { "Missing or oversized screenshot" }
            }
            val conv = activeEngine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(system), tools = emptyList(), automaticToolCalling = false
            ))
            val reducer = StreamingTextReducer()
            val emittedTools = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val parts = mutableListOf<Content>(Content.Text(prompt))
                image?.let { parts.add(Content.ImageBytes(it)) }
                val response = nativeInference.send(conv, Message.user(Contents.of(parts)),
                    INFERENCE_TIMEOUT_MS, E4bRuntimeBudgets.phone(spec).outputTokens) { partial ->
                    if (partial.toolCalls.isNotEmpty()) emittedTools.set(true)
                    extractRawText(partial)?.let(reducer::onSnapshot)
                }
                check(!emittedTools.get() && response.toolCalls.isEmpty()) { "Controller must not emit tool calls" }
                val text = reducer.fullText().trim().ifBlank { extractText(response).orEmpty() }
                check(text.isNotBlank()) { "Model returned no controller response" }
                Result.Success(text)
            } finally {
                closeRequestConversation(conv)
            }
        }

    /** Retain native handles rather than closing beneath an unacknowledged JNI callback. */
    private suspend fun closeRequestConversation(conv: Conversation) {
        withContext(kotlinx.coroutines.NonCancellable) {
            if (nativeInference.awaitNativeIdle()) {
                runCatching { conv.close() }.onFailure { retainedConversations.add(conv); cleanupUncertain = true; nativeQuarantined = true }
            } else { retainedConversations.add(conv); nativeQuarantined = true }
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
        return runPhoneInference("plan continuation") { _, spec ->
            val conv = conversation ?: return@runPhoneInference Result.Error("Planning conversation expired")
            val trimmed = observation.take(MAX_OBSERVATION_CHARS)
            Logger.d("GemmaPlanner: planNext after '$prevTool', observation=${trimmed.length} chars")
            val toolMessage = Message.tool(Contents.of(Content.ToolResponse(prevTool, mapOf("result" to trimmed))))
            extractValidatedToolCall(
                nativeInference.send(
                    conv, toolMessage, INFERENCE_TIMEOUT_MS,
                    E4bRuntimeBudgets.phone(spec).outputTokens
                )
            )
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
        return runPhoneInference("safety judge") { activeEngine, spec ->
            val prompt = buildSafetyJudgePrompt(toolName, argsJson, inputText)
            val conv = activeEngine.createConversation(
                ConversationConfig(systemInstruction = Contents.of(SAFETY_JUDGE_SYSTEM_INSTRUCTION))
            ).also { judgeConversation = it }
            val responseMessage = try {
                nativeInference.send(
                    conv, Message.user(Contents.of(prompt)), INFERENCE_TIMEOUT_MS,
                    SAFETY_OUTPUT_TOKENS
                )
            } finally {
                closeRequestConversation(conv)
                judgeConversation = null
            }
            val text = extractText(responseMessage) ?: ""
            Result.Success(parseVerdict(text))
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
     * unavailable, inference times out, or both bounded attempts produce unusable text, the caller
     * reports a recoverable chat failure. A question is never forwarded to the tool planner.
     */
    suspend fun chat(command: String, responseLanguage: String = ""): Result<String> {
        return runPhoneInference("chat") { activeEngine, spec ->
            val first = runChatAttempt(
                activeEngine = activeEngine,
                prompt = PromptBuilder.buildChatUserMessage(command, responseLanguage),
                outputTokens = E4bRuntimeBudgets.chat(spec).outputTokens
            )
            val firstAssessment = ChatAnswerValidator.assess(first)
            if (firstAssessment.isValid) {
                Result.Success(firstAssessment.normalized)
            } else {
                Logger.w("GemmaPlanner: rejected chat attempt 1 (${firstAssessment.reason})")
                val retry = runChatAttempt(
                    activeEngine = activeEngine,
                    prompt = PromptBuilder.buildChatRetryUserMessage(command, responseLanguage),
                    outputTokens = CHAT_RETRY_OUTPUT_TOKENS
                )
                val retryAssessment = ChatAnswerValidator.assess(retry)
                if (retryAssessment.isValid) {
                    Logger.i("GemmaPlanner: chat recovered on bounded retry")
                    Result.Success(retryAssessment.normalized)
                } else {
                    Logger.w("GemmaPlanner: rejected chat attempt 2 (${retryAssessment.reason})")
                    Result.Error("Gemma chat produced no meaningful answer")
                }
            }
        }
    }

    private suspend fun runChatAttempt(
        activeEngine: Engine,
        prompt: String,
        outputTokens: Int
    ): String {
        // LiteRT-LM's async callback can deliver token deltas instead of a final cumulative
        // Message. Keeping only the last callback reduced a complete answer to its final "." on the
        // physical device. Accumulate every callback under both delta and cumulative semantics.
        val reducer = StreamingTextReducer()
        val conv = activeEngine.createConversation(
            ConversationConfig(systemInstruction = Contents.of(PromptBuilder.buildChatSystemInstruction()))
        ).also { chatConversation = it }
        return try {
            val responseMessage = nativeInference.send(
                conv,
                Message.user(Contents.of(prompt)),
                INFERENCE_TIMEOUT_MS,
                outputTokens
            ) { partial ->
                extractRawText(partial)?.let(reducer::onSnapshot)
            }
            reducer.fullText().trim().ifBlank {
                extractText(responseMessage).orEmpty()
            }
        } finally {
            closeRequestConversation(conv)
            chatConversation = null
        }
    }

    private suspend fun <T> runPhoneInference(
        operation: String,
        block: suspend (Engine, BrainModelSpec) -> Result<T>
    ): Result<T> = E4bRuntimeCoordinator.operationMutex.withLock {
        val activeEngine = engine ?: return@withLock Result.Error("Gemma model not loaded")
        val spec = loadedSpec ?: return@withLock Result.Error("Gemma model profile missing")
        try {
            check(!nativeQuarantined && !cleanupUncertain) { "Native engine quarantined; request refused" }
            check(nativeInference.awaitNativeIdle()) { nativeQuarantined = true; "Native inference still active; request refused" }
            val iterator = retainedConversations.iterator()
            try { while (iterator.hasNext()) { iterator.next().close(); iterator.remove() } }
            catch (e: Exception) { cleanupUncertain = true; nativeQuarantined = true; throw e }
            E4bRuntimeCoordinator.transition(E4bRuntimeState.PHONE_INFERENCING, PHONE_OWNER, operation)
            block(activeEngine, spec)
        } catch (error: TimeoutCancellationException) {
            Logger.w("GemmaPlanner: $operation timed out after ${INFERENCE_TIMEOUT_MS}ms")
            Result.Error("Gemma $operation timed out", error)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.e("GemmaPlanner: $operation failed")
            Result.Error("Gemma $operation failed: ${error.message}", error)
        } finally {
            val idle = withContext(kotlinx.coroutines.NonCancellable) { nativeInference.awaitNativeIdle() }
            if (!idle) nativeQuarantined = true
            E4bRuntimeCoordinator.transition(
                if (isLoaded && idle && !nativeQuarantined && !cleanupUncertain && retainedConversations.isEmpty()) E4bRuntimeState.PHONE_READY else E4bRuntimeState.FAILED,
                PHONE_OWNER,
                activeBackend
            )
        }
    }

    private fun createPlanningConversation(
        activeEngine: Engine,
        spec: BrainModelSpec,
        command: String
    ): Conversation =
        activeEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PromptBuilder.buildSystemInstruction(spec.modelFamily)),
                tools = PlannerToolRouter.schemasFor(command).map { tool(CanonicalOpenApiTool(it)) },
                automaticToolCalling = false
            )
        )

    private suspend fun resetPlanningConversation() {
        conversation?.let { closeRequestConversation(it) }
        conversation = null
    }

    private fun speechFallback(command: String): ToolCall = ToolCall(
        "speak_response",
        JsonObject(mapOf("text" to JsonPrimitive(PlannerToolRouter.safeFallbackText(command))))
    )

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
                Logger.w("GemmaPlanner: judge verdict unparseable")
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
            Logger.d("GemmaPlanner: validated tool proposal")
            return Result.Success(ToolCall(name, args))
        }
        // No tool selected — surface the model text as a speak_response so the user hears it.
        val text = extractText(responseMessage) ?: "I'm not sure how to do that yet."
        Logger.d("GemmaPlanner: text response received")
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
    fun requestCancel(reason: String = "external stop") {
        nativeInference.cancelActive(reason)
    }

    suspend fun close(): Boolean {
        requestCancel("engine close")
        return E4bRuntimeCoordinator.operationMutex.withLock {
            E4bRuntimeCoordinator.transition(E4bRuntimeState.UNLOADING, PHONE_OWNER)
            if (!nativeInference.awaitNativeIdle()) {
                lastLoadError = "Native inference did not stop; engine close refused to prevent a JNI use-after-free"
                E4bRuntimeCoordinator.transition(E4bRuntimeState.FAILED, PHONE_OWNER, lastLoadError)
                return@withLock false
            }
            if (!withContext(Dispatchers.IO) { closeInternal() }) return@withLock false
            E4bRuntimeCoordinator.transition(E4bRuntimeState.UNLOADED)
            true
        }
    }

    private fun closeInternal(): Boolean {
        // A throwing close may already have freed native memory. No retry until process restart.
        if (cleanupUncertain) { E4bRuntimeCoordinator.quarantine(this); E4bRuntimeCoordinator.transition(E4bRuntimeState.FAILED, PHONE_OWNER, "Native cleanup quarantined; restart required"); return false }
        return try {
            val iterator = retainedConversations.iterator()
            while (iterator.hasNext()) { iterator.next().close(); iterator.remove() }
            conversation?.close(); conversation = null
            judgeConversation?.close(); judgeConversation = null
            chatConversation?.close(); chatConversation = null
            engine?.close(); engine = null
            isLoaded = false
            loadedSpec = null
            activeBackend = ""
            nativeQuarantined = false
            E4bRuntimeCoordinator.acknowledgeClosed(this)
            true
        } catch (e: Exception) {
            E4bRuntimeCoordinator.quarantine(this)
            cleanupUncertain = true
            nativeQuarantined = true
            E4bRuntimeCoordinator.transition(E4bRuntimeState.FAILED, PHONE_OWNER)
            lastLoadError = "Native cleanup refused/failed: ${e.message}"
            Logger.w("GemmaPlanner: $lastLoadError")
            false
        }
    }

    private fun extractText(message: Message): String? =
        // Concatenate ALL text fragments, not just the first — LiteRT-LM may split a response
        // across multiple Content.Text parts; keeping only the first collapsed multi-part answers
        // to a single fragment (the `?`/truncated final-card bug). Null when no non-blank text.
        ResponseTextJoiner.join(
            message.contents?.contents?.map { (it as? Content.Text)?.text } ?: emptyList()
        )

    /** Streaming extraction that preserves whitespace carried by individual token deltas. */
    private fun extractRawText(message: Message): String? {
        val fragments = message.contents?.contents
            ?.mapNotNull { (it as? Content.Text)?.text }
            .orEmpty()
        if (fragments.isEmpty()) return null
        return fragments.joinToString(separator = "").takeIf(String::isNotEmpty)
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

    companion object {
        private const val PHONE_OWNER = "phone-agent"
        private const val SAFETY_OUTPUT_TOKENS = 16
        private const val CHAT_RETRY_OUTPUT_TOKENS = 80
        private const val VISION_SYSTEM_INSTRUCTION =
            "Describe only visible evidence from the supplied image. Never invent objects or text."
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
