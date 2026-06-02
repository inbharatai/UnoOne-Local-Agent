package com.unoone.agent

import com.unoone.agent.localbrain.RuleBasedParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            "stop scanning",
            "stop barriers",
            "remove obstacles",
            "turn off obstacles",
            "disable barriers",
            "no more barriers"
        )
        for (trigger in triggers) {
            val toolCall = RuleBasedParser.parse(trigger)
            assertNotNull("Failed to parse deactivation trigger: $trigger", toolCall)
            assertEquals("deactivate_blind_aid", toolCall!!.tool)
        }
    }

    @Test
    fun testNoteCreationTriggers() {
        val toolCall = RuleBasedParser.parse("remember: pick up groceries")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
    }

    @Test
    fun testNoteCreationWithoutColon() {
        // "add note buy milk" should extract "buy milk" without leaking "add " prefix
        val toolCall = RuleBasedParser.parse("add note buy milk")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
        val content = toolCall.args["content"]?.toString()?.replace("\"", "") ?: ""
        assertEquals("buy milk", content)
    }

    @Test
    fun testCompoundCommand() {
        // "scroll down and go home" should produce a compound tool call with both parts.
        // Note: "open chrome and go home" doesn't reach the compound handler because "open chrome"
        // matches a standalone rule first — the compound handler is only reached when neither
        // half is a standalone prefix match.
        val toolCall = RuleBasedParser.parse("scroll down and go home")
        assertNotNull("Compound command should parse", toolCall)
        assertEquals("compound", toolCall!!.tool)
        assertNotNull(toolCall.args["first_tool"])
        assertNotNull(toolCall.args["second_tool"])
    }

    @Test
    fun testLongPressWithText() {
        val toolCall = RuleBasedParser.parse("long press on settings")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("long_press", toolCall.args["action"]?.toString()?.replace("\"", ""))
        assertEquals("settings", toolCall.args["target"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun testActivationNotConfusedByDeactivation() {
        // Ensure "deactivate blind aid" does NOT match activation
        val toolCall = RuleBasedParser.parse("deactivate blind aid")
        assertNotNull(toolCall)
        assertEquals("deactivate_blind_aid", toolCall!!.tool)

        // And "stop barriers" should be deactivation, not activation
        val toolCall2 = RuleBasedParser.parse("stop barriers")
        assertNotNull(toolCall2)
        assertEquals("deactivate_blind_aid", toolCall2!!.tool)
    }
}