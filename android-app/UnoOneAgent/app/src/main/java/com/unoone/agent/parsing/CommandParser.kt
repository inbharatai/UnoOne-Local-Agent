package com.unoone.agent.parsing

import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.core.interfaces.ICommandParser
import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.localbrain.ContextSnapshot
import com.unoone.agent.localbrain.LocalBrain
import com.unoone.agent.localbrain.RuleBasedParser
import com.unoone.agent.memory.MemoryModule
import com.unoone.agent.phonecontrol.OcrControl
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Parses user input into structured [ToolCall]s.
 *
 * - [RuleBasedParser] is always tried first: it is fast, deterministic, and works offline.
 * - If no rule matches and a Gemma model is loaded, the input + an enriched context snapshot are
 *   sent to [LocalBrain] via LiteRT-LM for planning. The snapshot pulls in recent notes, active
 *   skills, an OCR fallback, plus the recent-commands / last-result ring buffer the orchestrator
 *   maintains, so the model can disambiguate follow-up commands.
 */
class CommandParser(
    private val localBrain: LocalBrain = LocalBrain(),
    private val accessibilityControl: AccessibilityControl? = null,
    private val ocrControl: OcrControl? = null,
    private val memoryModule: MemoryModule? = null,
    private val noteDao: NoteDao? = null,
    private val skillDao: SkillDao? = null
) : ICommandParser {

    /**
     * Synchronous parse path. Only uses [RuleBasedParser]; it never blocks on LLM inference.
     */
    override fun parse(text: String): ToolCall? {
        return RuleBasedParser.parse(text)
    }

    /**
     * Asynchronous parse path. Tries rules first, then falls back to Gemma when loaded, passing
     * the recent-commands / last-result context to enrich the snapshot. Returns a plain [ToolCall]?
     * with no provenance — callers that need to know whether the LLM produced the call (the ReAct
     * loop) must use [parseAsyncWithProvenance].
     */
    override suspend fun parseAsync(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ToolCall? = parseAsyncWithProvenance(text, recentCommands, lastToolResult).toolCallOrNull()

    /**
     * Same parse path as [parseAsync] but returns the **origin** of the call too. The ReAct loop
     * only engages when the call came from the LLM ([ParseOutcome.Llm]); a rule-based match
     * ([ParseOutcome.Rule]) never started a conversation, so it cannot be continued with an
     * observation.
     */
    suspend fun parseAsyncWithProvenance(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ParseOutcome {
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ParseOutcome.Rule(ruleResult)

        if (localBrain.isModelLoaded()) {
            val snapshot = buildContextSnapshot(text, recentCommands, lastToolResult)
            val inferenceResult = localBrain.runInference(text, snapshot)
            if (inferenceResult is Result.Success) return ParseOutcome.Llm(inferenceResult.data)
        }
        return ParseOutcome.None
    }

    /**
     * Same parse path as [parseAsyncWithProvenance] (rules first, then the loaded LLM) but, when the
     * LLM path is taken, streams the model's partial text via [onDelta] as it is generated. A rule
     * match short-circuits before the LLM is reached, so no deltas are emitted for rule-handled
     * commands. The returned [ParseOutcome] is identical to [parseAsyncWithProvenance].
     *
     * Streaming is device-time-only; the caller wraps this in a try/catch fallback to
     * [parseAsyncWithProvenance] so a streaming failure degrades gracefully to the synchronous path.
     */
    suspend fun parseStreamingWithProvenance(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String,
        onDelta: (String) -> Unit
    ): ParseOutcome {
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ParseOutcome.Rule(ruleResult)

        if (localBrain.isModelLoaded()) {
            val snapshot = buildContextSnapshot(text, recentCommands, lastToolResult)
            val inferenceResult = localBrain.runInferenceStreaming(text, snapshot, onDelta)
            if (inferenceResult is Result.Success) return ParseOutcome.Llm(inferenceResult.data)
        }
        return ParseOutcome.None
    }

    /**
     * ReAct "Observe" step for the orchestrator: feeds the [observation] (result of [prevTool])
     * back into the live LLM conversation and returns the model's next proposed, validated tool
     * call. Only valid when a model is loaded; the orchestrator calls this only inside the bounded
     * loop after an LLM-planned call. Device-time verified.
     */
    suspend fun planNext(prevTool: String, observation: String): Result<ToolCall> =
        localBrain.planNext(prevTool, observation)

    /**
     * Second on-device safety-judge pass over a proposed action. Returns a [SafetyVerdict] the
     * orchestrator merges (escalate-only) with the keyword-classified risk. Device-time verified.
     */
    suspend fun judgeSafety(toolName: String, argsJson: String, inputText: String): Result<SafetyVerdict> =
        localBrain.judgeSafety(toolName, argsJson, inputText)

    /**
     * Multimodal vision description of a screenshot, for the `describe_scene` tool. INACTIVE with
     * the shipped text-only models; the orchestrator only calls this when `VISION_MODEL_ENABLED`.
     * On any Error the executor falls back to the OCR + context description. Device-time-only.
     */
    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> =
        localBrain.describeSceneWithVision(imageBytes, aspect)

    override fun sanitizeAndParse(rawInput: String): ToolCall? {
        val sanitized = InputSanitizer.sanitize(rawInput)
        if (sanitized.isBlank()) return null
        return parse(sanitized)
    }

    override fun isModelLoaded(): Boolean = localBrain.isModelLoaded()

    /** The profile currently loaded into the brain, or null when no model is loaded. */
    fun loadedProfile(): BrainModelSpec? = localBrain.loadedProfile()

    /** Actual runtime backend ("GPU"/"CPU") of the loaded brain, or "" if not loaded. */
    fun activeBackend(): String = localBrain.activeBackend()

    /** Last load error (empty on success) — surfaces device-compatibility status to the UI. */
    fun lastLoadError(): String = localBrain.lastLoadError()

    /**
     * Exposed so callers (e.g., tests) can load a model into this parser's brain.
     */
    suspend fun loadModel(modelPath: String): Result<Unit> = localBrain.loadModel(modelPath)

    /**
     * Profile-aware load — loads [modelPath] as [spec] (Gemma 4 E2B or Gemma 3n E4B) through the
     * same safe [GemmaPlanner] interface.
     */
    suspend fun loadModel(modelPath: String, spec: BrainModelSpec): Result<Unit> =
        localBrain.loadModel(modelPath, spec)

    /**
     * Unloads the Gemma brain, freeing native memory. Used by [com.unoone.agent.AgentOrchestrator]
     * on system memory pressure (see [com.unoone.agent.UnoOneApplication.onTrimMemory]). Safe to call
     * when no model is loaded; [LocalBrain.unloadModel] / [com.unoone.agent.localbrain.GemmaPlanner.close]
     * are idempotent.
     */
    fun unloadModel() = localBrain.unloadModel()

    /**
     * Builds a context snapshot for the LLM. Runs on [Dispatchers.Default] so that
     * accessibility / OCR / DAO / memory work does not block the caller thread. `internal` so
     * the enrichment (recent notes / active skills / recent commands / last result) is unit-testable
     * with fake DAOs without spinning up Robolectric or a real model.
     */
    internal suspend fun buildContextSnapshot(
        command: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ContextSnapshot = withContext(Dispatchers.Default) {
        val currentContext = accessibilityControl?.getCurrentContext() ?: ""
        val packageName = currentContext.substringBefore("/").ifBlank { "" }
        val activityName = currentContext.substringAfter("/", "").ifBlank { "" }
        val visibleText = accessibilityControl?.captureScreenText()
            ?.let { if (it is Result.Success) it.data.take(2_000) else "" } ?: ""

        // OCR is expensive (MediaProjection screenshot + ML Kit) — only run it as a fallback
        // when the accessibility tree gave us nothing on screen.
        val ocrText = if (visibleText.isBlank()) {
            try {
                ocrControl?.recognizeScreen()
                    ?.let { if (it is Result.Success) it.data.take(1_000) else "" }
                    ?: ""
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }

        val memoryContext = try {
            memoryModule?.getRelevantContext(command) ?: ""
        } catch (_: Exception) {
            ""
        }

        val recentNotes = try {
            noteDao?.recent(5)
                ?.map { note -> if (note.title.isNotBlank()) note.title else note.content.take(40) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val activeSkills = try {
            skillDao?.getEnabled()?.first()?.map { it.name } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        ContextSnapshot(
            currentPackage = packageName,
            currentActivity = activityName,
            visibleText = visibleText,
            ocrText = ocrText,
            recentNotes = recentNotes,
            userMemory = memoryContext,
            activeSkills = activeSkills,
            recentCommands = recentCommands,
            lastToolResult = lastToolResult
        )
    }
}
