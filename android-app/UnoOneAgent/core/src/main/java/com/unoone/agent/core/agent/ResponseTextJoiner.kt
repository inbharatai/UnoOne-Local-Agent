package com.unoone.agent.core.agent

/**
 * Joins the text fragments of a final LLM response into a single string.
 *
 * LiteRT-LM may split a model response across multiple `Content.Text` parts. The final extraction
 * must concatenate *all* text parts (in order), not just the first — otherwise a multi-part answer
 * collapses to its first fragment (observed as a truncated / `?` final card while the streaming
 * timeline showed the full answer).
 *
 * Pure-JVM so it can be unit-tested without the `litertlm-android` AAR (whose bytecode exceeds the
 * JDK 17 test JVM). Callers map their SDK-specific content parts to plain strings first, then call
 * [join]. Returns null when there is no non-blank text, preserving the null-means-no-text contract
 * that downstream fallbacks depend on (e.g. `extractText(...) ?: "I'm not sure how to do that yet."`).
 */
object ResponseTextJoiner {
    /**
     * Concatenates [fragments] (the text string extracted from each content part, in order),
     * skipping nulls, and returns the trimmed result — or null if it is blank.
     */
    fun join(fragments: List<String?>): String? {
        val joined = StringBuilder()
        for (fragment in fragments) {
            if (fragment != null) joined.append(fragment)
        }
        val text = joined.toString().trim()
        return text.takeIf { it.isNotBlank() }
    }
}