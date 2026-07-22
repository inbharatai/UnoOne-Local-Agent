package com.unoone.agent.voice

import kotlin.math.sqrt

/** Lightweight adaptive energy VAD used only to segment wake/command audio. */
class AdaptiveSpeechDetector {
    private var ambientRms = INITIAL_AMBIENT_RMS

    fun reset() { ambientRms = INITIAL_AMBIENT_RMS }

    fun hasSpeech(pcmData: ByteArray): Boolean {
        if (pcmData.size < 2) return false
        var sum = 0.0
        var samples = 0
        for (i in 0 until pcmData.size - 1 step 2) {
            val raw = (pcmData[i].toInt() and 0xFF) or ((pcmData[i + 1].toInt() and 0xFF) shl 8)
            val signed = if (raw >= 0x8000) raw - 0x10000 else raw
            sum += signed.toDouble() * signed.toDouble()
            samples++
        }
        val rms = sqrt(sum / samples.coerceAtLeast(1))
        val threshold = (ambientRms * SPEECH_MULTIPLIER).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val speech = rms >= threshold
        if (!speech) {
            ambientRms = (ambientRms * (1.0 - AMBIENT_ALPHA) + rms * AMBIENT_ALPHA)
                .coerceIn(MIN_AMBIENT, MAX_AMBIENT)
        }
        return speech
    }

    internal fun thresholdForTest(): Double =
        (ambientRms * SPEECH_MULTIPLIER).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    companion object {
        private const val INITIAL_AMBIENT_RMS = 120.0
        private const val AMBIENT_ALPHA = 0.08
        private const val SPEECH_MULTIPLIER = 3.2
        private const val MIN_AMBIENT = 40.0
        private const val MAX_AMBIENT = 550.0
        private const val MIN_THRESHOLD = 260.0
        private const val MAX_THRESHOLD = 1_760.0
    }
}
