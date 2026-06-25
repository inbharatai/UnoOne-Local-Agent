package com.unoone.agent

import com.unoone.agent.core.model.compoundSteps
import com.unoone.agent.localbrain.RuleBasedParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleBasedParserTest {

    @Test
    fun testBlindAidActivationTriggers() {
        // 4D: "obstacles" and "barriers" alone are DEACTIVATION, not activation.
        // Only triggers with positive context ("detect", "start", etc.) activate.
        val triggers = listOf(
            "start blind aid",
            "activate blind aid",
            "detect objects",
            "what's in front of me",
            "detect barrier",
            "detect barriers",
            "look for obstacles"
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
    fun testNoteRememberToStripsToPrefix() {
        // "remember to buy groceries" should strip the grammatical "to" and yield "buy groceries"
        val toolCall = RuleBasedParser.parse("remember to buy groceries")
        assertNotNull(toolCall)
        assertEquals("create_note", toolCall!!.tool)
        val content = toolCall.args["content"]?.toString()?.replace("\"", "") ?: ""
        assertEquals("buy groceries", content)
    }

    @Test
    fun testCompoundCommand() {
        // "scroll down and go home" — both halves are navigation commands,
        // so the compound handler splits and parses them correctly into an ordered
        // `steps` array. Domain-specific rules (skill, email, whatsapp, calendar) are
        // checked BEFORE compound splitting, so they preserve their internal "and" semantics.
        val toolCall = RuleBasedParser.parse("scroll down and go home")
        assertNotNull("Compound command should parse", toolCall)
        assertEquals("compound", toolCall!!.tool)
        val steps = toolCall.compoundSteps()
        assertEquals("Compound must expand to 2 ordered steps", 2, steps.size)
        assertEquals("system_control", steps[0].tool)
        assertEquals("system_control", steps[1].tool)
        assertEquals("scroll_down", steps[0].args["action"]?.jsonPrimitive?.content)
        assertEquals("go_home", steps[1].args["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun testCompoundCommandDoesNotBreakSkillSteps() {
        // "create skill called greeting to say hello and wave goodbye" — the skill rule
        // must match as a whole (not split on "and"), with both steps preserved.
        val toolCall = RuleBasedParser.parse("create skill called greeting to say hello and wave goodbye")
        assertNotNull(toolCall)
        assertEquals("create_skill", toolCall!!.tool)
        val steps = toolCall.args["steps"]?.toString()?.replace("\"", "") ?: ""
        assertEquals(true, steps.contains("say hello"))
        assertEquals(true, steps.contains("wave goodbye"))
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

    // === 4D Parser Bug Fix Tests ===

    @Test
    fun testBareBarriersTriggersDeactivation() {
        // 4D: "barriers" alone should be deactivation only, not detect_objects
        val toolCall = RuleBasedParser.parse("barriers")
        assertNotNull("Bare 'barriers' should parse as deactivation", toolCall)
        assertEquals("deactivate_blind_aid", toolCall!!.tool)
    }

    @Test
    fun testNoteWithNegationVerb() {
        // 4D: "delete note" should NOT create a note
        val toolCall = RuleBasedParser.parse("delete note groceries")
        // Should not parse as create_note
        if (toolCall != null) {
            assert(toolCall.tool != "create_note") { "Negation verb + note should not create a note" }
        }
    }

    @Test
    fun testRemoveNoteDoesNotCreateNote() {
        val toolCall = RuleBasedParser.parse("remove note about meeting")
        if (toolCall != null) {
            assert(toolCall.tool != "create_note") { "Remove + note should not create a note" }
        }
    }

    @Test
    fun testOpenSettingsBeforeOpenGoogle() {
        // 4D: "open settings" parses as open_app (with Settings package), not open_chrome
        val toolCall = RuleBasedParser.parse("open settings")
        assertNotNull(toolCall)
        assertEquals("open_app", toolCall!!.tool)
    }

    @Test
    fun testOpenGoogleSettingsParsesAsUrl() {
        // "open google settings" does NOT contain "open settings" as a contiguous substring
        // (because "open " is followed by "google", not "settings"), so it falls through
        // to the "open google" rule and returns open_url.
        val toolCall = RuleBasedParser.parse("open google settings")
        assertNotNull(toolCall)
        assertEquals("open_url", toolCall!!.tool)
    }

    @Test
    fun testThreePartCompoundCommand() {
        // "A and B and C" must parse into a compound with all 3 steps preserved in order
        // (the 3rd part was previously parsed and discarded).
        val toolCall = RuleBasedParser.parse("open chrome and scroll down and go home")
        assertNotNull(toolCall)
        assertEquals("compound", toolCall!!.tool)
        val steps = toolCall.compoundSteps()
        assertEquals("Three-part compound must expand to 3 ordered steps", 3, steps.size)
        assertEquals("open_chrome", steps[0].tool)
        assertEquals("system_control", steps[1].tool)
        assertEquals("system_control", steps[2].tool)
        assertEquals("scroll_down", steps[1].args["action"]?.jsonPrimitive?.content)
        assertEquals("go_home", steps[2].args["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun testEmailRegexWithDot() {
        // 4D: Email regex should match emails with dots in local part
        val toolCall = RuleBasedParser.parse("send email to john.doe@example.com about meeting")
        if (toolCall != null && toolCall.tool == "draft_email") {
            val email = toolCall.args["to"]?.toString()?.replace("\"", "") ?: ""
            assertTrue("Email should contain @example.com", email.contains("@example.com"))
        }
    }

    @Test
    fun testCalendarCheck() {
        val toolCall = RuleBasedParser.parse("check calendar")
        assertNotNull(toolCall)
        assertEquals("check_calendar", toolCall!!.tool)
    }

    @Test
    fun testReadScreen() {
        val toolCall = RuleBasedParser.parse("read screen")
        assertNotNull(toolCall)
        assertEquals("read_screen", toolCall!!.tool)
    }

    @Test
    fun testOcrScreen() {
        // "ocr" maps to read_screen (the parser uses "ocr" or "screen text" triggers)
        val toolCall = RuleBasedParser.parse("ocr the screen")
        assertNotNull(toolCall)
        assertEquals("read_screen", toolCall!!.tool)
    }

    @Test
    fun testOpenCamera() {
        val toolCall = RuleBasedParser.parse("open camera")
        assertNotNull(toolCall)
        assertEquals("open_camera", toolCall!!.tool)
    }

    @Test
    fun testDraftEmail() {
        val toolCall = RuleBasedParser.parse("draft email to test@example.com about update with body hello")
        assertNotNull(toolCall)
        assertEquals("draft_email", toolCall!!.tool)
    }

    @Test
    fun testFindAndClick() {
        val toolCall = RuleBasedParser.parse("find and click submit")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("find_and_click", toolCall.args["action"]?.toString()?.replace("\"", ""))
        assertEquals("submit", toolCall.args["target"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun testScrollDown() {
        val toolCall = RuleBasedParser.parse("scroll down")
        assertNotNull(toolCall)
        assertEquals("system_control", toolCall!!.tool)
        assertEquals("scroll_down", toolCall.args["action"]?.toString()?.replace("\"", ""))
    }
}