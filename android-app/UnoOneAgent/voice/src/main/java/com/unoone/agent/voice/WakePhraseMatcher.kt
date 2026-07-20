package com.unoone.agent.voice

import java.text.Normalizer

/**
 * Result of conservative wake matching. [command] is every word after the wake phrase; it is empty
 * only when the utterance was an activation by itself.
 */
data class WakePhraseMatch(
    val normalizedTranscript: String,
    val matchedPhrase: String,
    val confidence: Float,
    val command: String
)

/**
 * Natural UnoOne wake matching without broad ambient-speech activation.
 *
 * The matcher recognizes canonical forms, Hindi forms, and common STT substitutions. Ambiguous
 * substitutions such as "you know" are accepted only alone or before an activation/action token,
 * which keeps "you know what happened" negative.
 */
object WakePhraseMatcher {
    private data class Prefix(
        val value: String,
        val canonical: String,
        val confidence: Float,
        val ambiguousAlias: Boolean = false
    )

    private val prefixes = listOf(
        Prefix("turn on uno one", "turn on uno one", 1f),
        Prefix("turn on uno", "turn on uno", 1f),
        Prefix("wake up uno one", "wake up uno one", 1f),
        Prefix("wake up uno", "wake up uno", 1f),
        Prefix("start uno one", "start uno one", 1f),
        Prefix("start uno", "start uno", 1f),
        Prefix("okay uno one", "okay uno one", 1f),
        Prefix("okay uno", "okay uno", 1f),
        Prefix("ok uno one", "ok uno one", 1f),
        Prefix("ok uno", "ok uno", 1f),
        Prefix("hello uno one", "hello uno one", 1f),
        Prefix("hello uno", "hello uno", 1f),
        Prefix("hey uno one", "hey uno one", 1f),
        Prefix("hey uno", "hey uno", 1f),
        Prefix("hi uno one", "hi uno one", 1f),
        Prefix("hi uno", "hi uno", 1f),
        Prefix("listen to me", "listen to me", 1f),
        Prefix("uno one", "uno one", 1f),
        Prefix("unoone", "uno one", .98f),
        Prefix("unone", "uno one", .92f),
        Prefix("uno won", "uno one", .92f),
        Prefix("uno 1", "uno one", .95f),
        Prefix("you know one", "uno one", .84f, ambiguousAlias = true),
        Prefix("you no one", "uno one", .84f, ambiguousAlias = true),
        Prefix("you know", "uno", .82f, ambiguousAlias = true),
        Prefix("you no", "uno", .82f, ambiguousAlias = true),
        Prefix("u no", "uno", .82f, ambiguousAlias = true),
        Prefix("un o", "uno", .86f, ambiguousAlias = true),
        Prefix("uno", "uno", 1f),
        Prefix("listen", "listen", .96f),
        Prefix("मेरी बात सुनो", "मेरी बात सुनो", 1f),
        Prefix("यूनो वन", "यूनो वन", 1f),
        Prefix("यूनोवन", "यूनो वन", .98f),
        Prefix("यूनो", "यूनो", .98f),
        Prefix("सुनो", "सुनो", .96f)
    ).sortedByDescending { it.value.length }

    /**
     * Ambiguous English ASR aliases need an action/activation-shaped continuation. This list is
     * intentionally bounded; a generic question such as "you know what happened" is not a wake.
     */
    private val safeAliasContinuations = setOf(
        "start", "on", "open", "launch", "listen", "help", "please", "suno", "chalu",
        "shuru", "blind", "read", "describe", "go", "scroll", "click", "tap", "schedule",
        "calendar", "whatsapp", "gmail", "email", "mail", "hindi", "english", "stop",
        "pause", "resume", "camera", "create", "draft", "send", "fill", "search"
    )

    /** Activation-only tails. These are stripped only when no real command follows them. */
    private val activationOnlyTails = setOf(
        "start", "on", "listen", "help", "open", "please", "suno",
        "chalu karo", "shuru karo", "start karo"
    )

    fun match(transcript: String): WakePhraseMatch? {
        val normalized = WakePhraseNormalizer.normalize(transcript)
        if (normalized.isBlank()) return null
        val prefix = prefixes.firstOrNull {
            normalized == it.value || normalized.startsWith("${it.value} ")
        } ?: fuzzyUnoPrefix(normalized)
        if (prefix == null) return null

        var remainder = normalized.removePrefix(prefix.value).trim()
        var originalRemainder = originalTokens(transcript)
            .drop(prefix.value.split(' ').size)
        if (prefix.ambiguousAlias && remainder.isNotBlank()) {
            val first = remainder.substringBefore(' ')
            if (first !in safeAliasContinuations) return null
        }

        // "Uno on, open WhatsApp" uses "on" as an activation filler. Preserve "start" in
        // "Uno start blind mode", where it is part of the actual command.
        if (remainder.startsWith("on ") && remainder.length > 3) {
            remainder = remainder.removePrefix("on ").trim()
            originalRemainder = originalRemainder.drop(1)
        } else if (remainder in activationOnlyTails) {
            remainder = ""
            originalRemainder = emptyList()
        }
        return WakePhraseMatch(
            normalizedTranscript = normalized,
            matchedPhrase = prefix.canonical,
            confidence = prefix.confidence,
            command = if (remainder.isBlank()) "" else originalRemainder.joinToString(" ")
        )
    }

    private fun originalTokens(transcript: String): List<String> =
        Normalizer.normalize(transcript, Normalizer.Form.NFKC)
            .replace(Regex("""[^\p{L}\p{M}\p{N}@.+]+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter(String::isNotBlank)

    /**
     * One-edit tolerance for the distinctive leading "uno" token. It is allowed only alone or
     * before a safe continuation, preventing general fuzzy matching across ambient conversation.
     */
    private fun fuzzyUnoPrefix(normalized: String): Prefix? {
        val first = normalized.substringBefore(' ')
        if (first.length !in 2..4 || editDistance(first, "uno") > 1) return null
        val remainder = normalized.removePrefix(first).trim()
        if (remainder.isNotBlank() && remainder.substringBefore(' ') !in safeAliasContinuations) {
            return null
        }
        return Prefix(first, "uno", .76f, ambiguousAlias = true)
    }

    private fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        a.forEachIndexed { i, ca ->
            current[0] = i + 1
            b.forEachIndexed { j, cb ->
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (ca == cb) 0 else 1
                )
            }
            current.copyInto(previous)
        }
        return previous[b.length]
    }
}
