package com.unoone.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityPolicyTest {
    @Test
    fun normalizedRecorderAmplitudeCanCrossSpeechThreshold() {
        assertFalse(VoiceActivityPolicy.isSpeech(0.005f))
        assertTrue(VoiceActivityPolicy.isSpeech(0.05f))
        assertTrue(VoiceActivityPolicy.SPEECH_THRESHOLD in 0f..1f)
    }

    @Test
    fun handsFreeTimingDoesNotForceTwelveSecondWait() {
        assertTrue(VoiceActivityPolicy.TRAILING_SILENCE_MS <= 1_000L)
        assertTrue(VoiceActivityPolicy.MAX_UTTERANCE_MS <= 8_000L)
    }
}
