package com.unoone.agent.localbrain

/**
 * Defensive contract for free-text CHAT output.
 *
 * LiteRT-LM can complete successfully while returning only punctuation (for example "."). That is
 * not a usable answer and must never be displayed, spoken, logged as success, or forwarded to the
 * phone-action planner. Keep this validator deterministic so the same contract can run in JVM tests
 * and at the orchestration boundary.
 */
object ChatAnswerValidator {
    data class Assessment(
        val isValid: Boolean,
        val normalized: String,
        val reason: String = ""
    )

    fun assess(raw: String?): Assessment {
        val normalized = raw
            ?.replace('\u0000', ' ')
            ?.replace(Regex("[\\p{Z}\\s]+"), " ")
            ?.trim()
            .orEmpty()

        if (normalized.isEmpty()) {
            return Assessment(false, normalized, "empty")
        }

        val lowered = normalized.lowercase()
        if (CONTROL_MARKERS.any(lowered::contains)) {
            return Assessment(false, normalized, "tool/control syntax")
        }

        val letters = normalized.filter(Char::isLetter)
        val digits = normalized.filter(Char::isDigit)
        if (letters.isEmpty() && digits.isEmpty()) {
            return Assessment(false, normalized, "punctuation only")
        }

        // A one-letter fragment is almost always a truncated decode. A one-digit factual answer
        // can be legitimate, so numeric replies remain valid.
        if (letters.length == 1 && digits.isEmpty()) {
            return Assessment(false, normalized, "truncated lexical output")
        }

        val lexical = letters + digits
        if (lexical.length >= 4 && lexical.map(Char::lowercaseChar).distinct().size == 1) {
            return Assessment(false, normalized, "repeated-character output")
        }

        return Assessment(true, normalized)
    }

    private val CONTROL_MARKERS = listOf(
        "<start_of_turn>",
        "<end_of_turn>",
        "\"tool_calls\"",
        "\"function_call\"",
        "\"tool\":"
    )
}
