package com.unoone.agent.voice

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapturePolicyTest {
    @Test
    fun `blocks cellular call audio`() {
        assertTrue(VoiceCapturePolicy.isCallAudioActive(AudioManager.MODE_IN_CALL))
    }

    @Test
    fun `blocks VoIP call audio`() {
        assertTrue(VoiceCapturePolicy.isCallAudioActive(AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `allows normal media mode`() {
        assertFalse(VoiceCapturePolicy.isCallAudioActive(AudioManager.MODE_NORMAL))
    }
}
