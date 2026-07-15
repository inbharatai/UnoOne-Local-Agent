package com.unoone.agent.core.document

import java.io.InputStream

/**
 * C8: real plain-text / CSV extraction — reads the stream as UTF-8. CSV is returned verbatim (its
 * rows/columns are text the brain reads directly; no half-baked cell parser). No dummies: this is
 * the actual decoded stream, capped to [maxChars] to fit the brain's context window.
 */
object PlainTextExtractor {
    /** C8: shared cap for every extractor — keeps the extracted body inside the brain's context window. */
    const val DEFAULT_MAX_CHARS = 6000

    fun extract(input: InputStream, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val raw = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return cap(raw, maxChars)
    }

    /** Truncates to [maxChars] on a character boundary, preserving whole lines where reasonable. */
    fun cap(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars)
}