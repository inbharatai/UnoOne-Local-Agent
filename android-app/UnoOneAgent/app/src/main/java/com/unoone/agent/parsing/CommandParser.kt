package com.unoone.agent.parsing

import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.core.interfaces.ICommandParser
import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
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
 * - If no rule matches and Gemma 4 is loaded by the phone-agent mode, the input and enriched context
 *   are sent to [LocalBrain].
 * - When Gemma is exclusively leased to Secure Browser, rules remain available but phone-agent LLM
 *   planning stays intentionally unavailable. [isModelLoaded] still reports occupied so the
 *   orchestrator does not misclassify the lease as a crash and self-heal a second model copy.
 */
class CommandParser(
    private val localBrain: LocalBrain = LocalBrain(),
    private val accessibilityControl: AccessibilityControl? = null,
    private val ocrControl: OcrControl? = null,
    private val memoryModule: MemoryModule? = null,
    private val noteDao: NoteDao? = null,
    private val skillDao: SkillDao? = null
) : ICommandParser {

    override fun parse(text: String): ToolCall? = RuleBasedParser.parse(text)

    override suspend fun parseAsync(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ToolCall? = parseAsyncWithProvenance(text, recentCommands, lastToolResult).toolCallOrNull()

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

    suspend fun planNext(prevTool: String, observation: String): Result<ToolCall> =
        localBrain.planNext(prevTool, observation)

    suspend fun judgeSafety(toolName: String, argsJson: String, inputText: String): Result<SafetyVerdict> =
        localBrain.judgeSafety(toolName, argsJson, inputText)

    /**
     * CHAT lane: one conversational inference on a dedicated tool-less conversation. The caller
     * ([com.unoone.agent.AgentOrchestrator]) only invokes this for question-shaped, action-free
     * input; on any Error/blank answer it falls back to the agent pipeline. Device-time verified.
     */
    suspend fun chat(text: String): Result<String> = localBrain.chat(text)

    suspend fun describeSceneWithVision(imageBytes: ByteArray, aspect: String): Result<String> =
        localBrain.describeSceneWithVision(imageBytes, aspect)

    override fun sanitizeAndParse(rawInput: String): ToolCall? {
        val sanitized = InputSanitizer.sanitize(rawInput)
        if (sanitized.isBlank()) return null
        return parse(sanitized)
    }

    /**
     * Reports model availability to orchestration and lifecycle code.
     *
     * An external lease counts as intentionally occupied, preventing the normal self-heal path from
     * loading another Gemma engine. Actual phone-agent inference methods still check
     * [localBrain.isModelLoaded] directly and therefore never call the browser-owned conversation.
     */
    override fun isModelLoaded(): Boolean =
        localBrain.isModelLoaded() || ExclusiveBrainLeaseState.isActive()

    fun loadedProfile(): BrainModelSpec? = localBrain.loadedProfile()

    fun activeBackend(): String = localBrain.activeBackend()

    fun lastLoadError(): String = localBrain.lastLoadError()

    suspend fun loadModel(modelPath: String): Result<Unit> = localBrain.loadModel(modelPath)

    suspend fun loadModel(modelPath: String, spec: BrainModelSpec): Result<Unit> =
        localBrain.loadModel(modelPath, spec)

    fun unloadModel() = localBrain.unloadModel()

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
