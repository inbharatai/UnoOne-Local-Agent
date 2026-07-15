package com.unoone.agent.core.agent

import com.unoone.agent.core.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [NarrationPolicy] — the eyes-free step-narration mapping. Pure logic, no Android.
 * Asserts the coordination rule (final answer steps stay silent) and the milestone/failure phrases
 * a blind user hears for VOICE commands.
 */
class NarrationPolicyTest {

    @Test
    fun finalAnswerStepsStaySilentToAvoidDoubleSpeak() {
        // The FAST/CHAT/AGENT lanes speak the real answer; narration must not echo it.
        assertNull(NarrationPolicy.narrationFor(AgentStatus.SPEAKING, "Response", "the full answer"))
        assertNull(NarrationPolicy.narrationFor(AgentStatus.DONE, "Done", "the full answer"))
        assertNull(NarrationPolicy.narrationFor(AgentStatus.UNDERSTANDING, "Thinking", "explain god"))
        assertNull(NarrationPolicy.narrationFor(AgentStatus.UNDERSTANDING, "Understanding Command", "open calendar"))
    }

    @Test
    fun agentPlanMilestoneSpeaksFriendlyToolPhrase() {
        assertEquals(
            "Opening Chrome",
            NarrationPolicy.narrationFor(AgentStatus.TOOL_SELECTED, "Agent Plan", "Action: open_chrome")
        )
        assertEquals(
            "Creating a note",
            NarrationPolicy.narrationFor(AgentStatus.TOOL_SELECTED, "Agent Plan", "Action: create_note")
        )
        // Unknown tool falls back to a generic planning cue, not silence.
        assertEquals(
            "Planning",
            NarrationPolicy.narrationFor(AgentStatus.TOOL_SELECTED, "Agent Plan", "Action: something_new")
        )
    }

    @Test
    fun followUpPlanExtractsToolFromDetail() {
        assertEquals(
            "Opening calendar",
            NarrationPolicy.narrationFor(AgentStatus.TOOL_SELECTED, "Agent Plan", "Follow-up: open_calendar")
        )
    }

    @Test
    fun executingSpeaksFriendlyPhrase() {
        assertEquals(
            "Opening Chrome",
            NarrationPolicy.narrationFor(AgentStatus.EXECUTING, "Agent Active", "Executing open_chrome...")
        )
        // Compound-step details carry the raw tool name.
        assertEquals(
            "Creating a note",
            NarrationPolicy.narrationFor(AgentStatus.EXECUTING, "Compound Step 1/2", "create_note")
        )
        assertEquals(
            "Starting blind aid",
            NarrationPolicy.narrationFor(AgentStatus.EXECUTING, "Compound Step 2/2", "detect_objects")
        )
    }

    @Test
    fun safetyCheckSpeaksAccessConfirmationAndChecking() {
        assertEquals(
            "Checking safety",
            NarrationPolicy.narrationFor(AgentStatus.SAFETY_CHECK, "Safety Filter", "Risk: CONFIRM")
        )
        assertEquals(
            "I need access. Needs system access for read_screen",
            NarrationPolicy.narrationFor(AgentStatus.SAFETY_CHECK, "Access Required", "Needs system access for read_screen")
        )
        assertEquals(
            "Please confirm. Opening the camera is a visible action",
            NarrationPolicy.narrationFor(AgentStatus.SAFETY_CHECK, "Confirmation Required", "Opening the camera is a visible action")
        )
        // Internal judge escalation stays silent.
        assertNull(NarrationPolicy.narrationFor(AgentStatus.SAFETY_CHECK, "Safety Judge", "Escalated CONFIRM → STRONG_CONFIRM"))
    }

    @Test
    fun failureSpeaksTheDetailForBlindUsers() {
        assertEquals(
            "Action blocked for security.",
            NarrationPolicy.narrationFor(AgentStatus.FAILED, "Security Block", "Action blocked for security.")
        )
        assertEquals(
            "Cancelled",
            NarrationPolicy.narrationFor(AgentStatus.FAILED, "Cancelled", "Cancelled")
        )
        assertEquals(
            "No command detected after sanitization.",
            NarrationPolicy.narrationFor(AgentStatus.FAILED, "Empty Input", "No command detected after sanitization.")
        )
        // Blank failure detail stays silent (nothing to say).
        assertNull(NarrationPolicy.narrationFor(AgentStatus.FAILED, "Empty", ""))
    }

    @Test
    fun verifyingSpeaksRecoveryAndVerifyMilestones() {
        assertEquals("Verifying", NarrationPolicy.narrationFor(AgentStatus.VERIFYING, "Verifying Outcome", "Compound complete"))
        assertEquals("Brain reloaded", NarrationPolicy.narrationFor(AgentStatus.VERIFYING, "Recovered", "Brain reloaded."))
    }

    @Test
    fun toolPhraseIsConsistent() {
        assertEquals("Opening calendar", NarrationPolicy.toolPhrase("open_calendar"))
        assertEquals("Starting blind aid", NarrationPolicy.toolPhrase("detect_objects"))
        // speak_response is the answer itself — no milestone phrase.
        assertNull(NarrationPolicy.toolPhrase("speak_response"))
        assertNull(NarrationPolicy.toolPhrase("unknown_tool"))
    }
}