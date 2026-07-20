package com.unoone.agent.ui.viewmodel

import com.unoone.agent.core.model.Result
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePackActivationTest {
    @Test
    fun `activation requires both offline speech engines`() {
        assertTrue(
            voiceLanguageActivationSucceeded(
                Result.Success(Unit),
                Result.Success(Unit)
            )
        )
        assertFalse(
            voiceLanguageActivationSucceeded(
                Result.Error("STT failed"),
                Result.Success(Unit)
            )
        )
        assertFalse(
            voiceLanguageActivationSucceeded(
                Result.Success(Unit),
                Result.Error("TTS failed")
            )
        )
    }

    @Test
    fun `only hardened English and Hindi packs are exposed`() {
        assertTrue(isExposedVoiceLanguage("en-IN"))
        assertTrue(isExposedVoiceLanguage("hi-IN"))
        assertFalse(isExposedVoiceLanguage("bn-IN"))
        assertFalse(isExposedVoiceLanguage("ta-IN"))
    }
}
