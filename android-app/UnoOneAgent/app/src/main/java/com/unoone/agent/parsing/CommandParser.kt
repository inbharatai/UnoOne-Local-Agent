package com.unoone.agent.parsing

import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.core.interfaces.ICommandParser
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
     * the recent-commands / last-result context to enrich the snapshot.
     */
    override suspend fun parseAsync(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ToolCall? {
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ruleResult

        if (localBrain.isModelLoaded()) {
            val snapshot = buildContextSnapshot(text, recentCommands, lastToolResult)
            val inferenceResult = localBrain.runInference(text, snapshot)
            if (inferenceResult is Result.Success) return inferenceResult.data
        }
        return null
    }

    override fun sanitizeAndParse(rawInput: String): ToolCall? {
        val sanitized = InputSanitizer.sanitize(rawInput)
        if (sanitized.isBlank()) return null
        return parse(sanitized)
    }

    override fun isModelLoaded(): Boolean = localBrain.isModelLoaded()

    /**
     * Exposed so callers (e.g., tests) can load a model into this parser's brain.
     */
    suspend fun loadModel(modelPath: String): Result<Unit> = localBrain.loadModel(modelPath)

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
