package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VoiceResponseLocalizerTest {

    @Test
    fun sideEffectToolUsesSelectedLanguage() {
        val english = "Opened WhatsApp draft."
        assertEquals(english, VoiceResponseLocalizer.toolResult("send_whatsapp", english, "en"))
        assertEquals(
            "खोल रहा हूँ।",
            VoiceResponseLocalizer.toolResult("send_whatsapp", english, "hi-IN")
        )
        assertNotEquals(
            english,
            VoiceResponseLocalizer.toolResult("send_whatsapp", english, "ta")
        )
    }

    @Test
    fun contentBearingResultsAreNotDiscarded() {
        val ocr = "कुल राशि ५०० रुपये"
        assertEquals(ocr, VoiceResponseLocalizer.toolResult("ocr_screen", ocr, "hi"))
    }

    @Test
    fun milestonesAreLocalizedForEverySupportedIndicLanguage() {
        listOf("hi", "bn", "ta", "te", "kn", "ml").forEach { language ->
            assertNotEquals("Reading screen", VoiceResponseLocalizer.milestone("Reading screen", language))
        }
    }

    @Test
    fun failuresAndCalendarResultsReplyInEverySelectedLanguage() {
        listOf("hi", "bn", "ta", "te", "kn", "ml").forEach { language ->
            assertNotEquals(
                "The task could not be completed. Please try again.",
                VoiceResponseLocalizer.failure(language)
            )
            val calendar = VoiceResponseLocalizer.toolResult(
                "check_calendar",
                "You have 3 events.",
                language
            )
            assertNotEquals("You have 3 events.", calendar)
            org.junit.Assert.assertTrue(calendar.contains("3"))
        }
    }
}
