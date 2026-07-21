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
    fun shortUnoWakePhraseHasANativeKwsEntry() {
        assertTrue(WakePhrases.LIST.contains("uno"))
        assertTrue(WakePhrases.KWS_ENTRIES.any { it.endsWith("@uno") })
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
    fun extractsNativeHindiOneBreathCommand() {
        val cases = mapOf(
            "सुनो, स्क्रीन पढ़ो" to "स्क्रीन पढ़ो",
            "मेरी बात सुनो: ब्लाइंड एड चालू करो" to "ब्लाइंड एड चालू करो"
        )
        cases.forEach { (utterance, command) ->
            assertEquals(utterance, command, WakePhrases.commandAfterWakePhrase(utterance))
        }
    }

    @Test
    fun naturalActivationVariantsMatch() {
        val positives = listOf(
            "Uno", "Uno One", "UnoOne", "Hey Uno", "Hello Uno", "Hi Uno", "Okay Uno",
            "Start Uno", "Start Uno One", "Uno start", "Uno One start", "Uno on",
            "Uno One on", "Turn on Uno", "Wake up Uno", "Uno listen", "Uno help",
            "Uno open", "Uno please", "Uno suno", "Uno chalu karo", "Uno shuru karo",
            "Uno start karo", "You know start", "You no", "You know one", "You no one",
            "Uno won", "Uno 1", "Unone", "U no", "Un o"
        )
        positives.forEach { utterance ->
            assertTrue("$utterance should activate", WakePhraseMatcher.match(utterance) != null)
        }
    }

    @Test
    fun ordinaryConversationDoesNotActivate() {
        val negatives = listOf(
            "You know what happened",
            "I have no one",
            "Start the video",
            "Turn the phone on",
            "Random background conversation"
        )
        negatives.forEach { utterance ->
            assertEquals("$utterance must stay ambient", null, WakePhraseMatcher.match(utterance))
        }
    }

    @Test
    fun stripsWakeAndActivationFillersWithoutDroppingCommand() {
        assertEquals("start blind mode", WakePhrases.commandAfterWakePhrase("Uno start blind mode"))
        assertEquals("open WhatsApp", WakePhrases.commandAfterWakePhrase("Uno on, open WhatsApp"))
        assertEquals("Hindi mein jawab do", WakePhrases.commandAfterWakePhrase("Hey Uno, Hindi mein jawab do"))
    }
}
