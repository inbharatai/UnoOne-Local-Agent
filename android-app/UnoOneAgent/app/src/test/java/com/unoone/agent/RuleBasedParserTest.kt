package com.unoone.agent

import com.unoone.agent.localbrain.RuleBasedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RuleBasedParserTest {

    @Test
    fun testBlindAidActivationTriggers() {
        val triggers = listOf(
            "start blind aid",
            "activate blind aid",
            "detect objects",
            "what's in front of me",
            "detect barrier",
            "obstacles"
        )
        for (trigger in triggers) {
            val toolCall = RuleBasedParser.parse(trigger)
            assertNotNull("Failed to parse trigger: $trigger", toolCall)
            assertEquals("detect_objects", toolCall!!.tool)
        }
    }

    @Test
    fun testBlindAidDeactivationTriggers() {
        val triggers = listOf(
            "stop blind aid",
            "deactivate blind aid",
            "turn off blind aid",
            "stop scanning"
        )
        for (trigger in triggers) {
            val toolCall = RuleBasedParser.parse(trigger)
            assertNotNull("Failed to parse trigger: $trigger", toolCall)
            assertEquals("deactivate_blind_aid", toolCall!!.tool)
        }
    }

    @Test
    fun testNoteCreationTriggers() {
        val toolCall = RuleBasedParser.parse("remember: pick up groceries")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
    }
}
