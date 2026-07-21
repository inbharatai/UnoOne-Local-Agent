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
        assertEquals("en", asr.language)
        assertEquals("speech/languages/en-IN/tts", VoiceLanguage.ttsFolder("en"))
    }

    @Test
    fun hindiUsesSharedOmnilingualAsr() {
        val asr = VoiceLanguage.asrSpec("hi")
        assertEquals("speech/shared/sherpa-asr-indic", asr.folder)
        assertEquals(SttMode.OMNILINGUAL, asr.mode)
        assertEquals("hi", asr.language)
    }

    @Test
    fun hindiUsesItsOfflineTtsFolder() {
        assertEquals("speech/languages/hi-IN/tts", VoiceLanguage.ttsFolder("hi"))
    }

    @Test
    fun kwsFolderUsesSharedVadPath() {
        assertEquals("speech/shared/vad", VoiceLanguage.KWS_FOLDER)
    }

    @Test
    fun kwsFallsBackToInstalledEnglishTransducer() {
        assertEquals(
            listOf("speech/shared/vad", "speech/shared/sherpa-asr-en"),
            VoiceLanguage.kwsFolders()
        )
    }

    @Test
    fun normalizeFallsBackToEnglishForUnknownOrBlank() {
        assertEquals("en", VoiceLanguage.normalize(null))
        assertEquals("en", VoiceLanguage.normalize(""))
        assertEquals("en", VoiceLanguage.normalize("   "))
        assertEquals("en", VoiceLanguage.normalize("xyz"))
        assertEquals("en", VoiceLanguage.normalize("fr"))
        assertEquals("hi", VoiceLanguage.normalize("hi"))
        assertEquals("en", VoiceLanguage.normalize("ml"))
    }

    @Test
    fun supportedListIsEnglishAndHindiOnly() {
        val codes = VoiceLanguage.SUPPORTED.map { it.code }
        assertEquals(listOf("en", "hi"), codes)
    }

    @Test
    fun displayNameIsHumanReadable() {
        assertEquals("English", VoiceLanguage.displayName("en"))
        assertEquals("Hindi", VoiceLanguage.displayName("hi"))
        assertEquals("English", VoiceLanguage.displayName("ml"))
        assertNotEquals("en", VoiceLanguage.displayName("hi"))
    }

    @Test
    fun everySupportedLanguageHasAnIndianLocaleAndNativeTestPhrase() {
        val expected = mapOf(
            "en" to "en-IN",
            "hi" to "hi-IN"
        )
        VoiceLanguage.SUPPORTED.forEach { language ->
            assertEquals(expected.getValue(language.code), VoiceLanguage.localeTag(language.code))
            assertTrue(VoiceLanguage.testPhrase(language.code).isNotBlank())
        }
    }

    @Test
    fun englishAsrDiffersFromIndicAsrMode() {
        assertNotEquals(VoiceLanguage.asrSpec("en").mode, VoiceLanguage.asrSpec("hi").mode)
        assertTrue(VoiceLanguage.isSupported("hi"))
    }

    @Test
    fun hindiHasANonEnglishNativeWakeCue() {
        assertEquals("Yes, I'm listening.", VoiceLanguage.wakeCue("en"))
        val cue = VoiceLanguage.wakeCue("hi")
        assertTrue(cue.isNotBlank())
        assertNotEquals("Hindi wake cue must not fall back to English", VoiceLanguage.wakeCue("en"), cue)
    }

    @Test
    fun explicitVoiceCommandsSelectEnglishOrHindi() {
        listOf(
            "Speak in Hindi",
            "Hindi mein bolo",
            "Hindi mein jawab do",
            "अब हिंदी में बोलो"
        ).forEach { assertEquals(it, "hi", VoiceLanguage.requestedFromCommand(it)) }
        listOf(
            "Speak in English",
            "English mein bolo",
            "इंग्लिश में बोलो"
        ).forEach { assertEquals(it, "en", VoiceLanguage.requestedFromCommand(it)) }
    }

    @Test
    fun languageMentionsWithoutAnExplicitChangeDoNotSwitch() {
        listOf(
            "Translate this Hindi sentence",
            "Open the English calendar",
            "Send a message saying Hindi class is cancelled",
            "What is the Hindi word for calendar?"
        ).forEach { assertEquals(it, null, VoiceLanguage.requestedFromCommand(it)) }
    }

    @Test
    fun explicitLanguageRequestCanFollowAnActionWithoutDiscardingIt() {
        val trailing = VoiceLanguage.extractRequest(
            "blind mode start karo aur Hindi mein jawab do"
        )
        assertEquals("hi", trailing?.code)
        assertEquals("blind mode start karo", trailing?.remainingCommand)

        val leading = VoiceLanguage.extractRequest(
            "अब हिंदी में बोलो और ब्लाइंड मोड चालू करो"
        )
        assertEquals("hi", leading?.code)
        assertEquals("ब्लाइंड मोड चालू करो", leading?.remainingCommand)
    }
}
