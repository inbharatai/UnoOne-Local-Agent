package com.unoone.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for [WakePhrases] — the eyes-free wake-phrase list. Pure data; the actual KWS
 * tokenization + initialization + live wake-accuracy are device-time gates (the Sherpa native
 * library does not load under a JDK 17 test JVM), documented in `DEVICE_VERIFICATION.md`.
 */
class WakePhrasesTest {

    @Test
    fun listenIsAWakePhrase() {
        // The core eyes-free ask: a blind user can wake the app by saying "listen".
        assertTrue(
            "wake phrases must include 'listen' (got: ${WakePhrases.LIST})",
            WakePhrases.LIST.contains("listen")
        )
    }

    @Test
    fun originalWakeWordIsRetained() {
        assertTrue(
            "original 'uno one' wake word must stay for users trained on it (got: ${WakePhrases.LIST})",
            WakePhrases.LIST.contains("uno one")
        )
    }

    @Test
    fun noDuplicatePhrases() {
        assertEquals(
            "wake phrases must not duplicate (duplicates would waste KWS decoder slots)",
            WakePhrases.LIST.toSet().size,
            WakePhrases.LIST.size
        )
    }

    @Test
    fun everyWakePhraseHasAnEncodedKwsEntry() {
        assertEquals(WakePhrases.LIST.size, WakePhrases.KWS_ENTRIES.size)
        WakePhrases.KWS_ENTRIES.forEach { entry ->
            assertTrue(entry.contains(" :"))
            assertTrue(entry.contains(" #"))
            assertTrue(entry.contains(" @"))
            assertFalse(entry.startsWith("uno one"))
            assertFalse(entry.startsWith("listen"))
        }
    }

    @Test
    fun stripsWakePhraseFromOneBreathCommand() {
        assertEquals("open Chrome", WakePhrases.stripFromCommand("Uno One, open Chrome"))
        assertEquals("create a note", WakePhrases.stripFromCommand("UnoOne create a note"))
        assertEquals("read the screen", WakePhrases.stripFromCommand("Listen: read the screen"))
    }

    @Test
    fun doesNotStripListenInsideCommand() {
        assertEquals("play my listen later playlist", WakePhrases.stripFromCommand("play my listen later playlist"))
    }

    @Test
    fun extractsOneBreathCommandOnlyAfterLeadingWakePhrase() {
        assertEquals("open Blind Aid", WakePhrases.commandAfterWakePhrase("Uno One, open Blind Aid"))
        assertEquals("read my screen", WakePhrases.commandAfterWakePhrase("Listen to me: read my screen"))
        assertEquals("", WakePhrases.commandAfterWakePhrase("listen"))
        assertEquals(null, WakePhrases.commandAfterWakePhrase("please listen and open Blind Aid"))
    }

    @Test
    fun extractsNativeOneBreathCommandsForEveryIndicLanguage() {
        val cases = mapOf(
            "सुनो, स्क्रीन पढ़ो" to "स्क्रीन पढ़ो",
            "শোনো: স্ক্রিন পড়ো" to "স্ক্রিন পড়ো",
            "கேள், திரையை படி" to "திரையை படி",
            "విను: స్క్రీన్ చదువు" to "స్క్రీన్ చదువు",
            "ಕೇಳು, ಪರದೆಯನ್ನು ಓದು" to "ಪರದೆಯನ್ನು ಓದು",
            "കേൾക്കൂ: സ്ക്രീൻ വായിക്കുക" to "സ്ക്രീൻ വായിക്കുക"
        )
        cases.forEach { (utterance, command) ->
            assertEquals(utterance, command, WakePhrases.commandAfterWakePhrase(utterance))
        }
    }
}
