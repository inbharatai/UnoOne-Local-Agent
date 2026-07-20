package com.unoone.agent.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Pure transcript normalization used by both keyword-spotter recovery and ordinary offline STT.
 *
 * It deliberately does not turn arbitrary "you know ..." conversation into a wake phrase. Likely
 * STT substitutions are represented as tokens and are accepted conservatively by
 * [WakePhraseMatcher], where the surrounding words can be checked.
 */
object WakePhraseNormalizer {
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('१', '1')
            // \p{M} is required for Devanagari vowel signs and other combining marks.
            .replace(Regex("""[^\p{L}\p{M}\p{N}@.+]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
