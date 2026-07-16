package com.unoone.agent.skills

import com.unoone.agent.storage.entity.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPoliciesTest {
    @Test
    fun triggerMatchingUsesWordBoundariesAndLongestMatch() {
        val mail = SkillEntity(id = 1, name = "Mail", triggerPhrases = "mail", stepsJson = "[]")
        val morning = SkillEntity(
            id = 2,
            name = "Morning",
            triggerPhrases = "morning routine,run morning routine",
            stepsJson = "[]"
        )

        assertNull(SkillTriggerMatcher.bestMatch("draft an email", listOf(mail)))
        assertEquals(morning, SkillTriggerMatcher.bestMatch("please run morning routine now", listOf(mail, morning)))
        assertEquals(mail, SkillTriggerMatcher.bestMatch("mail", listOf(mail, morning)))
    }

    @Test
    fun learningOnlySuggestsBoundedNonSensitiveRoutines() {
        assertTrue(SkillLearningPolicy.shouldSuggest(3))
        assertEquals("open calendar", SkillLearningPolicy.suggestionFor("open calendar", "open_calendar")!!.steps.single())
        assertNull(SkillLearningPolicy.suggestionFor("message 9999999999 hello", "send_whatsapp"))
        assertNull(SkillLearningPolicy.suggestionFor("draft private email", "draft_email"))
    }
}
