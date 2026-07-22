package com.unoone.agent.voice

import java.text.Normalizer

/**
 * Exact, local-only spoken decisions for an already pending safety confirmation.
 *
 * This deliberately accepts a small set of complete acknowledgements only. A new command such as
 * "yes open Chrome" remains a command rather than accidentally authorizing a pending sensitive
 * action. Strong confirmations require the explicit word "confirm", matching the visible dialog.
 */
object VoiceConfirmationPolicy {
    fun prompt(requiresExplicitConfirm: Boolean): String =
        if (requiresExplicitConfirm) {
            "Security confirmation needed. Say Uno confirm to continue, or say Uno cancel."
        } else {
            "Please say Uno yes to continue, or say Uno cancel."
        }

    fun decision(transcript: String, requiresExplicitConfirm: Boolean): Boolean? {
        val normalized = normalize(transcript)
        if (normalized in DENY) return false
        return if (requiresExplicitConfirm) {
            normalized.takeIf { it in STRONG_ALLOW }?.let { true }
        } else {
            normalized.takeIf { it in ALLOW }?.let { true }
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val ALLOW = setOf(
        "yes", "yes please", "allow", "allow it", "go ahead", "proceed", "okay", "ok",
        "हाँ", "हां", "हाँ करो", "हां करो", "करो", "ठीक है", "haan", "ha", "haan karo"
    )

    private val STRONG_ALLOW = setOf(
        "confirm", "confirm please", "yes confirm", "confirm करो", "कन्फर्म", "कन्फर्म करो"
    )

    private val DENY = setOf(
        "no", "no thanks", "cancel", "deny", "stop", "do not", "dont", "don't",
        "नहीं", "नही", "मत करो", "रद्द", "nahi", "nahin"
    )
}
