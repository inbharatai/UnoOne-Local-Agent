package com.unoone.agent.voice

/**
 * End-of-speech policy for AudioRecorder's normalized RMS amplitude (0.0..1.0).
 */
object VoiceActivityPolicy {
    const val SPEECH_THRESHOLD = 0.018f
    const val TRAILING_SILENCE_MS = 850L
    const val MAX_UTTERANCE_MS = 8_000L

    fun isSpeech(normalizedRms: Float): Boolean =
        normalizedRms.coerceIn(0f, 1f) >= SPEECH_THRESHOLD
}
