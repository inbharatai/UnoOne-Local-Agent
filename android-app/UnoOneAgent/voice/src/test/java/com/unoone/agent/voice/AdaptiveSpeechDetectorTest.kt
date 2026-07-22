package com.unoone.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSpeechDetectorTest {
    @Test fun `ambient calibration adapts without treating quiet noise as speech`() {
        val detector = AdaptiveSpeechDetector()
        repeat(20) { assertFalse(detector.hasSpeech(pcm(90))) }
        assertTrue(detector.thresholdForTest() >= 260.0)
        assertTrue(detector.hasSpeech(pcm(1_200)))
    }

    @Test fun `signed negative PCM energy is decoded correctly`() {
        val detector = AdaptiveSpeechDetector()
        assertTrue(detector.hasSpeech(pcm(-1_400)))
    }

    private fun pcm(sample: Int): ByteArray = ByteArray(3_200).also { bytes ->
        val value = sample and 0xFFFF
        for (i in bytes.indices step 2) {
            bytes[i] = (value and 0xFF).toByte()
            bytes[i + 1] = ((value ushr 8) and 0xFF).toByte()
        }
    }
}
