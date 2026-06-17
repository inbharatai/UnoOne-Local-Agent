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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses user input into structured [ToolCall]s.
 *
 * - [RuleBasedParser] is always tried first: it is fast, deterministic, and works offline.
 * - If no rule matches and a Gemma model is loaded, the input + context snapshot are sent to
 *   [LocalBrain] via LiteRT-LM for planning.
 */
class CommandParser(
    private val localBrain: LocalBrain = LocalBrain(),
    private val accessibilityControl: AccessibilityControl? = null,
    private val ocrControl: OcrControl? = null,
    private val memoryModule: MemoryModule? = null
) : ICommandParser {

    /**
     * Synchronous parse path. Only uses [RuleBasedParser]; it never blocks on LLM inference.
     */
    override fun parse(text: String): ToolCall? {
        return RuleBasedParser.parse(text)
    }

    /**
     * Asynchronous parse path. Tries rules first, then falls back to Gemma when loaded.
     */
    override suspend fun parseAsync(text: String): ToolCall? {
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ruleResult

        if (localBrain.isModelLoaded()) {
            val snapshot = buildContextSnapshot(text)
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
     * Builds a context snapshot for the LLM. Runs on [Dispatchers.Default] so that
     * accessibility / memory work does not block the caller thread.
     */
    private suspend fun buildContextSnapshot(command: String): ContextSnapshot =
        withContext(Dispatchers.Default) {
            val packageName = accessibilityControl?.getCurrentContext()?.substringBefore("/") ?: ""
            val activityName = accessibilityControl?.getCurrentContext()?.substringAfter("/", "") ?: ""
            val visibleText = accessibilityControl?.captureScreenText()
                ?.let { if (it is Result.Success) it.data.take(2_000) else "" } ?: ""

            val memoryContext = try {
                memoryModule?.getRelevantContext(command) ?: ""
            } catch (e: Exception) {
                ""
            }

            ContextSnapshot(
                currentPackage = packageName,
                currentActivity = activityName,
                visibleText = visibleText,
                ocrText = "",
                recentNotes = emptyList(),
                userMemory = memoryContext,
                activeSkills = emptyList()
            )
        }
}
