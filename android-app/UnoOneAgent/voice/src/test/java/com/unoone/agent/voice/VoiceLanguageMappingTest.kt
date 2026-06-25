package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure language → model-folder/mode mapping in [VoiceLanguage]. No Android/native deps
 * are touched, so this runs as a plain JVM unit test.
 */
class VoiceLanguageMappingTest {

    @Test
    fun englishUsesTransducerAndCoquiTts() {
        val asr = VoiceLanguage.asrSpec("en")
        assertEquals("sherpa-asr-en", asr.folder)
        assertEquals(SttMode.TRANSDUCER, asr.mode)
        assertEquals("en", asr.whisperLanguage)
        assertEquals("sherpa-tts-en", VoiceLanguage.ttsFolder("en"))
    }

    @Test
    fun indicLanguagesUseSharedWhisperAsrAndPerLanguageMmsTts() {
        listOf("hi", "bn", "ta", "te", "kn", "ml").forEach { lang ->
            val asr = VoiceLanguage.asrSpec(lang)
            assertEquals("sherpa-asr-whisper", asr.folder)
            assertEquals(SttMode.WHISPER, asr.mode)
            // The whisper `language` field is the 2-letter code (whisper-tiny multilingual supports these).
            assertEquals(lang, asr.whisperLanguage)
        }
    }

    @Test
    fun ttsFolderPerIndicLanguage() {
        assertEquals("sherpa-tts-hin", VoiceLanguage.ttsFolder("hi"))
        assertEquals("sherpa-tts-ben", VoiceLanguage.ttsFolder("bn"))
        assertEquals("sherpa-tts-tam", VoiceLanguage.ttsFolder("ta"))
        assertEquals("sherpa-tts-tel", VoiceLanguage.ttsFolder("te"))
        assertEquals("sherpa-tts-kan", VoiceLanguage.ttsFolder("kn"))
        assertEquals("sherpa-tts-mal", VoiceLanguage.ttsFolder("ml"))
        // Each Indic language maps to a distinct folder.
        val folders = listOf("hi", "bn", "ta", "te", "kn", "ml").map { VoiceLanguage.ttsFolder(it) }
        assertEquals(folders.size, folders.toSet().size)
    }

    @Test
    fun kwsFolderIsAlwaysEnglishVad() {
        assertEquals("vad", VoiceLanguage.KWS_FOLDER)
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
        // Guards against an accidental regression that routes English through whisper.
        assertNotEquals(VoiceLanguage.asrSpec("en").mode, VoiceLanguage.asrSpec("hi").mode)
        assertTrue(VoiceLanguage.isSupported("ta"))
    }
}