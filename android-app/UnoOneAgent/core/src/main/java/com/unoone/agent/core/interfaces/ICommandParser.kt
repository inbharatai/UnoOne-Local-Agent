package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.ToolCall

/**
 * Abstraction for command parsing. Tries rule-based parsing first,
 * then falls back to ML-based inference if available.
 */
interface ICommandParser {
    fun parse(text: String): ToolCall?
    fun sanitizeAndParse(rawInput: String): ToolCall?
    fun isModelLoaded(): Boolean

    /** Rule-first, then LLM fallback (no conversation context). */
    suspend fun parseAsync(text: String): ToolCall? = parseAsync(text, emptyList(), "")

    /**
     * Rule-first, then LLM fallback, enriching the LLM context snapshot with the recent commands
     * and the last tool result so the model can disambiguate follow-ups ("do it again", "the
     * second one", etc.). Only the [text] is parsed; the context is advisory.
     */
    suspend fun parseAsync(
        text: String,
        recentCommands: List<String>,
        lastToolResult: String
    ): ToolCall?
}