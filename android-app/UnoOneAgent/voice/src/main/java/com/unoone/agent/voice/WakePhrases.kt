package com.unoone.agent.voice

/**
 * Eyes-free (WS2) wake phrases for the offline keyword spotter. "listen" is the core ask for a blind
 * user; "uno one" is retained for users trained on the original wake word.
 *
 * Tokenization note: each phrase is written verbatim to the Sherpa-ONNX keywords file. "uno one" is
 * already verified on the Xiaomi 14. "listen" (a single English word) is expected to tokenize via
 * the shared English BPE `tokens.txt`, but KWS initialization AND live wake-accuracy with the new
 * phrases are **device-time gates** (see `DEVICE_VERIFICATION.md`) — not JVM-assertable, since the
 * Sherpa native library does not load under a JDK 17 test JVM. If "listen to me" fails to tokenize on
 * the device, drop it from [LIST] and keep "uno one" + "listen".
 */
object WakePhrases {
    val LIST: List<String> = listOf("uno one", "listen", "listen to me")

    private val TRANSCRIPT_PREFIXES = listOf("listen to me", "uno one", "unoone", "listen")

    /** Removes only a leading wake phrase while preserving identical words inside the command. */
    fun stripFromCommand(transcript: String): String {
        val trimmed = transcript.trim()
        val lower = trimmed.lowercase()
        val prefix = TRANSCRIPT_PREFIXES.firstOrNull { phrase ->
            lower == phrase || lower.startsWith("$phrase ") ||
                lower.startsWith("$phrase,") || lower.startsWith("$phrase:") ||
                lower.startsWith("$phrase-")
        } ?: return trimmed
        return trimmed.drop(prefix.length).trimStart(' ', ',', '.', ':', '-', '—')
    }
}
