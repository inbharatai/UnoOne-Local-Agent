package com.unoone.agent.voice.stt

import kotlin.math.max

/**
 * Deterministic quality estimate for Sherpa recognizers that do not expose token probabilities.
 *
 * This is intentionally not presented as acoustic confidence. It catches empty, truncated,
 * repeated and implausible decodes so uncertain speech is retried once instead of being executed.
 */
object TranscriptQuality {
    fun score(text: String, sampleCount: Int, sampleRate: Int = 16_000): Float {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return 0f

        val lexical = normalized.filter(Char::isLetterOrDigit)
        if (lexical.isEmpty()) return 0f
        if (lexical.length == 1) return 0.2f

        var score = 0.86f
        val words = normalized.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter(String::isNotBlank)
        val durationSeconds = sampleCount.toFloat() / max(1, sampleRate)

        if (words.size == 1) score -= 0.10f
        if (durationSeconds >= 2.5f && lexical.length < 4) score -= 0.22f
        if (durationSeconds >= 4f && words.size <= 2) score -= 0.14f

        if (words.size >= 3) {
            val mostRepeated = words.groupingBy { it }.eachCount().maxOf { it.value }
            if (mostRepeated.toFloat() / words.size >= 0.67f) score -= 0.35f
        }

        val distinctLexical = lexical.map(Char::lowercaseChar).distinct().size
        if (lexical.length >= 5 && distinctLexical <= 2) score -= 0.35f
        if ('\uFFFD' in normalized || '\u0000' in normalized) score -= 0.4f

        return score.coerceIn(0f, 1f)
    }
}
