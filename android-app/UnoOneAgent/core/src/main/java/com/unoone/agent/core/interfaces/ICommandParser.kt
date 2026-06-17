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
    suspend fun parseAsync(text: String): ToolCall?
}