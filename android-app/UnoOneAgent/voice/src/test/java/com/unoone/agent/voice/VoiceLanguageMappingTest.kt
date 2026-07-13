package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the language-to-runtime path mapping without Android or native dependencies. */
class VoiceLanguageMappingTest {

    @Test
    fun englishUsesTransducerAndCoquiTts() {
        val asr = VoiceLanguage.asrSpec("en")
        assertEquals("speech/shared/sherpa-asr-en", asr.folder)
        assertEquals(SttMode.TRANSDUCER, asr.mode)
        assertEquals("en", asr.whisperLanguage)
        assertEquals("speech/languages/en-IN/tts", VoiceLanguage.ttsFolder("en"))
    }

    @Test
    fun indicLanguagesUseSharedWhisperAsrAndPerLanguageMmsTts() {
        listOf("hi", "bn", "ta", "te", "kn", "ml").forEach { lang ->
            val asr = VoiceLanguage.asrSpec(lang)
            assertEquals("speech/shared/sherpa-asr-whisper", asr.folder)
            assertEquals(SttMode.WHISPER, asr.mode)
            assertEquals(lang, asr.whisperLanguage)
        }
    }

    @Test
    fun ttsFolderPerIndicLanguage() {
        assertEquals("speech/languages/hi-IN/tts", VoiceLanguage.ttsFolder("hi"))
        assertEquals("speech/languages/bn-IN/tts", VoiceLanguage.ttsFolder("bn"))
        assertEquals("speech/languages/ta-IN/tts", VoiceLanguage.ttsFolder("ta"))
        assertEquals("speech/languages/te-IN/tts", VoiceLanguage.ttsFolder("te"))
        assertEquals("speech/languages/kn-IN/tts", VoiceLanguage.ttsFolder("kn"))
        assertEquals("speech/languages/ml-IN/tts", VoiceLanguage.ttsFolder("ml"))
        val folders = listOf("hi", "bn", "ta", "te", "kn", "ml").map { VoiceLanguage.ttsFolder(it) }
        assertEquals(folders.size, folders.toSet().size)
    }

    @Test
    fun kwsFolderUsesSharedVadPath() {
        assertEquals("speech/shared/vad", VoiceLanguage.KWS_FOLDER)
    }

    @Test
    fun normalizeFallsBackToEnglishForUnknownOrBlank() {
        assertEquals("en", VoiceLanguage.normalize(null))
        assertEquals("en", VoiceLanguage.normalize(""))
        assertEquals("en", VoiceLanguage.normalize("   "))
        assertEquals("en", VoiceLanguage.normalize("xyz"))
        assertEquals("en", VoiceLanguage.normalize("fr"))
        assertEquals("hi", VoiceLanguage.normalize("hi"))
        assertEquals("ml", VoiceLanguage.normalize("ml"))
    }

    @Test
    fun supportedListHasAllSevenInDisplayOrder() {
        val codes = VoiceLanguage.SUPPORTED.map { it.code }
        assertEquals(listOf("en", "hi", "bn", "ta", "te", "kn", "ml"), codes)
    }

    @Test
    fun displayNameIsHumanReadable() {
        assertEquals("English", VoiceLanguage.displayName("en"))
        assertEquals("Hindi", VoiceLanguage.displayName("hi"))
        assertEquals("Malayalam", VoiceLanguage.displayName("ml"))
        assertNotEquals("en", VoiceLanguage.displayName("hi"))
    }

    @Test
    fun englishAsrDiffersFromIndicAsrMode() {
        assertNotEquals(VoiceLanguage.asrSpec("en").mode, VoiceLanguage.asrSpec("hi").mode)
        assertTrue(VoiceLanguage.isSupported("ta"))
    }
}
