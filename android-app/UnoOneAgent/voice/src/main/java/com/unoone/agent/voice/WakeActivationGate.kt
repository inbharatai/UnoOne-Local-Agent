package com.unoone.agent.voice

/**
 * Process-local debounce for wake detections.
 *
 * Native KWS and the offline-STT fallback observe the same speech burst on adjacent audio chunks.
 * Without a shared gate, both paths can activate UnoOne for one utterance. The gate uses monotonic
 * time supplied by the caller and stores no transcript or other private audio-derived content.
 */
class WakeActivationGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun tryActivate(nowMs: Long): Boolean {
        require(nowMs >= 0L) { "Monotonic time must be non-negative" }
        val elapsed = if (lastAcceptedAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastAcceptedAtMs
        if (elapsed in 0 until cooldownMs) return false
        lastAcceptedAtMs = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        lastAcceptedAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1_800L
    }
}
