package com.unoone.agent.voice

/**
 * Eyes-free (WS2) wake phrases for the offline keyword spotter. "listen" is the core ask for a blind
 * user; "uno one" is retained for users trained on the original wake word.
 *
 * The native keyword spotter is the low-latency path. [commandAfterWakePhrase] is the independent
 * offline-STT fallback: it makes hands-free activation usable when a device/model combination
 * initializes KWS successfully but does not detect a real spoken phrase reliably.
 */
object WakePhrases {
    val LIST: List<String> = listOf("uno one", "listen", "listen to me")

    /**
     * BPE-tokenized keyword entries for the English streaming Zipformer model declared in
     * `models_manifest.json`. Sherpa KWS does not accept plain phrases here: passing `uno one`
     * directly makes its native constructor abort because `uno` is not a token in `tokens.txt`.
     * Scores and thresholds are deliberately per phrase because the common word "listen" needs a
     * stricter trigger than the more distinctive "uno one".
     */
    val KWS_ENTRIES: List<String> = listOf(
        "▁UN O ▁ONE :2.0 #0.25 @uno_one",
        "▁LI S TEN :1.5 #0.35 @listen",
        "▁LI S TEN ▁TO ▁ME :1.5 #0.25 @listen_to_me"
    )

    /**
     * Prefixes accepted by the independent Omnilingual-STT wake fallback. The low-latency native
     * KWS remains English because the shipped streaming transducer is English-only; these native
     * phrases make a complete one-breath wake command work in every language UnoOne exposes.
     * Longer phrases must precede their shorter forms.
     */
    private val TRANSCRIPT_PREFIXES = listOf(
        "listen to me", "uno one", "unoone", "listen",
        "मेरी बात सुनो", "यूनो वन", "यूनोवन", "सुनो",
        "আমার কথা শোনো", "ইউনো ওয়ান", "ইউনোওয়ান", "শোনো",
        "கேளுங்கள்", "யூனோ ஒன்", "யூனோஒன்", "கேள்",
        "వినండి", "యునో వన్", "యునోవన్", "విను",
        "ಕೇಳಿ", "ಯುನೋ ಒನ್", "ಯುನೋಒನ್", "ಕೇಳು",
        "കേൾക്കുക", "യൂനോ വൺ", "യൂനോവൺ", "കേൾക്കൂ"
    )

    /**
     * Returns the command following a leading wake phrase, an empty string when the utterance is
     * only a wake phrase, or null when this is ordinary ambient speech.
     */
    fun commandAfterWakePhrase(transcript: String): String? {
        val trimmed = transcript.trim()
        val lower = trimmed.lowercase()
        val prefix = TRANSCRIPT_PREFIXES.firstOrNull { phrase ->
            lower == phrase || lower.startsWith("$phrase ") ||
                lower.startsWith("$phrase,") || lower.startsWith("$phrase:") ||
                lower.startsWith("$phrase-")
        } ?: return null
        return trimmed.drop(prefix.length).trimStart(' ', ',', '.', ':', '-', '—')
    }

    /** Removes only a leading wake phrase while preserving identical words inside the command. */
    fun stripFromCommand(transcript: String): String {
        return commandAfterWakePhrase(transcript) ?: transcript.trim()
    }
}
