package com.unoone.agent.core.util

/**
 * Extractive summarizer with no ML dependency — works fully offline.
 *
 * Picks the highest-scoring sentences, where score = content-word count (words longer than 3
 * chars starting with a letter) plus a small bonus for sentences near the start (leads tend to be
 * summaries). Deliberately simple and deterministic: it produces a reasonable condensed version for
 * the `summarize_text` tool without pulling in a model, and never invents content (purely selects
 * from the input).
 */
object TextSummarizer {

    private const val MAX_SENTENCES = 5
    private const val MIN_SENTENCE_CHARS = 20

    /**
     * Summarizes [text] to roughly [ratio] of its sentences (clamped to [maxSentences]).
     * Returns the original text trimmed if it is already short.
     */
    fun summarize(text: String, ratio: Float = 0.3f, maxSentences: Int = MAX_SENTENCES): String {
        val cleaned = text.trim()
        if (cleaned.length <= MIN_SENTENCE_CHARS * 3) return cleaned

        val sentences = splitSentences(cleaned)
        if (sentences.size <= 2) return cleaned

        val targetCount = (sentences.size * ratio.coerceIn(0.1f, 0.9f))
            .toInt()
            .coerceIn(1, maxSentences)

        // Score: content-word count + position bonus (earlier = higher).
        val scored = sentences.mapIndexed { index, sentence ->
            val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
            val contentWords = words.count { it.length > 3 && it.first().isLetter() }
            val positionBonus = (sentences.size - index) * 0.25
            index to (contentWords + positionBonus)
        }

        val selected = scored.sortedByDescending { it.second }
            .take(targetCount)
            .sortedBy { it.first } // restore original order
            .map { sentences[it.first] }

        return selected.joinToString(" ").trim()
    }

    private fun splitSentences(text: String): List<String> {
        // Split on whitespace that follows a sentence terminator (English .!? and Devanagari danda),
        // keeping the terminator attached to each sentence. Robust for English and Hindi.
        val raw = text.split(Regex("(?<=[.!?।])\\s+"))
        return raw.map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_CHARS }
            .ifEmpty { listOf(text.trim()) }
    }
}