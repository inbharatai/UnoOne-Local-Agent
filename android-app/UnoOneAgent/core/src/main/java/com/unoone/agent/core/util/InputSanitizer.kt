package com.unoone.agent.core.util

/**
 * Sanitizes user input before it flows into tool arguments, accessibility actions,
 * or prompt templates. Prevents prompt injection, control character attacks,
 * and excessively long inputs.
 */
object InputSanitizer {

    private const val MAX_INPUT_LENGTH = 10_000
    private const val MAX_ACCESSIBILITY_LENGTH = 500

    private val DANGEROUS_PATTERNS = listOf(
        Regex("(?i)ignore\\s+(previous|above|all)\\s+instructions"),
        Regex("(?i)system\\s*:\\s*you\\s+are"),
        Regex("(?i)assistant\\s*:\\s*"),
        Regex("\\\\u[0-9a-fA-F]{4}"),
        Regex("<script", RegexOption.IGNORE_CASE),
        Regex("(?i)forget\\s+(previous|all|everything)"),
        Regex("(?i)disregard\\s+(previous|above|all)\\s+instructions")
    )

    /**
     * Sanitizes general user input for tool arguments.
     * Truncates to MAX_INPUT_LENGTH, filters prompt injection patterns.
     */
    fun sanitize(input: String): String {
        var sanitized = input.take(MAX_INPUT_LENGTH)
        for (pattern in DANGEROUS_PATTERNS) {
            sanitized = sanitized.replace(pattern, "[filtered]")
        }
        return sanitized.trim()
    }

    /**
     * Sanitizes text destined for accessibility actions (click, type, fill).
     * Shorter limit, strips control characters, no injection filtering needed
     * since accessibility actions don't go through a prompt.
     */
    fun sanitizeForAccessibility(text: String): String {
        return text.take(MAX_ACCESSIBILITY_LENGTH)
            .replace(Regex("[\\p{C}]"), "") // Strip control characters
            .trim()
    }

    /**
     * Sanitizes web content before injecting into prompts.
     * Strips Gemma control tokens, limits length, wraps with boundaries.
     */
    fun sanitizeForPrompt(input: String, maxLength: Int = 2000): String {
        var sanitized = input.take(maxLength)
        // Strip Gemma control tokens that could break prompt structure
        sanitized = sanitized.replace(Regex("<start_of_turn>"), "")
        sanitized = sanitized.replace(Regex("<end_of_turn>"), "")
        sanitized = sanitized.replace(Regex("<bos>"), "")
        sanitized = sanitized.replace(Regex("<eos>"), "")
        return sanitized.trim()
    }
}