package com.unoone.agent.parsing

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.localbrain.LocalBrain
import com.unoone.agent.localbrain.RuleBasedParser

/**
 * Parses user input into structured ToolCalls.
 * Tries RuleBasedParser first (offline, deterministic), then falls back to
 * LocalBrain (ML-based inference) if a model is loaded.
 */
class CommandParser(
    private val localBrain: LocalBrain = LocalBrain()
) {

    /**
     * Parse raw user input into a ToolCall.
     * Returns null if the input cannot be parsed into any known action.
     */
    fun parse(text: String): ToolCall? {
        // Try rule-based parser first (fast, offline, deterministic)
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ruleResult

        // Fall back to ML-based inference if a model is loaded
        if (localBrain.isModelLoaded()) {
            val inferenceResult = localBrain.runInference(text)
            if (inferenceResult is Result.Success) return inferenceResult.data
        }
        return null
    }

    /**
     * Sanitize input before parsing to prevent injection attacks.
     */
    fun sanitizeAndParse(rawInput: String): ToolCall? {
        val sanitized = InputSanitizer.sanitize(rawInput)
        if (sanitized.isBlank()) return null
        return parse(sanitized)
    }

    fun isModelLoaded(): Boolean = localBrain.isModelLoaded()
}