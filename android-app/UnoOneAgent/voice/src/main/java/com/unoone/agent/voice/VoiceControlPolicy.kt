package com.unoone.agent.voice

/** Exact bounded control envelope; no fuzzy aliases, substring commands or compound approvals. */
object VoiceControlPolicy {
    private val wake = Regex("""^(?:uno\s+one|unoone|uno|यूनो\s+वन|यूनोवन|यूनो)(?=$|[\s.,!?;:।])(?:[\s.,!?;:।]+)?""", RegexOption.IGNORE_CASE)
    fun payload(text: String): String {
        val value = text.trim()
        return wake.find(value)?.let { value.substring(it.range.last + 1).trim() } ?: value
    }
    fun isStop(text: String): Boolean {
        if (text.length > 80) return false
        return payload(text).trim().trimEnd('.', ',', '!', '?', '।').trim()
            .lowercase(java.util.Locale.ROOT) in setOf("stop", "cancel", "रुको", "रद्द")
    }
    fun routeStop(text: String, cancel: () -> Unit): Boolean {
        if (!isStop(text)) return false
        cancel()
        return true
    }
}
