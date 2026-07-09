package com.unoone.agent.core.agent

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the ReAct loop's termination/control logic. The actual LiteRT-LM
 * multi-turn inference (feeding a `Content.ToolResponse` back) lives in `GemmaPlanner.planNext`
 * and is device-time verified — it cannot be JVM-tested because `litertlm-android` ships bytecode
 * newer than the JDK 17 test JVM can load. These tests pin the *decision rules* that bound that
 * loop: when to continue, when to stop, and which stop reason is reported.
 */
class ReActLoopControllerTest {

    private fun call(tool: String, vararg args: Pair<String, String>): ToolCall =
        if (args.isEmpty()) ToolCall(tool, JsonObject(emptyMap()))
        else ToolCall(tool, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }))

    private fun speak(text: String): ToolCall =
        ToolCall("speak_response", JsonObject(mapOf("text" to JsonPrimitive(text))))

    // --- engagement gate -----------------------------------------------------

    @Test
    fun shouldEngageOnlyForObservationTools() {
        for (tool in listOf("search_notes", "summarize_text", "web_search", "read_screen",
                "ocr_screen", "detect_objects", "voice_recording", "check_calendar")) {
            assertTrue("$tool should engage the loop", ReActLoopController.shouldEngage(tool))
        }
    }

    @Test
    fun shouldNotEngageForOneShotOrSpeechTools() {
        for (tool in listOf("open_app", "open_chrome", "create_note", "send_whatsapp",
                "system_control", "delete_all_notes", "speak_response", "open_camera")) {
            assertTrue("$tool should NOT engage the loop", !ReActLoopController.shouldEngage(tool))
        }
    }

    @Test
    fun shouldNotEngageForUnknownTool() {
        // The gate runs before the brain's unknown-tool rejection; an unknown name simply does not
        // engage the loop (the first call still executes only if canonical — but that is enforced
        // elsewhere, not by this gate).
        assertTrue(!ReActLoopController.shouldEngage("not_a_tool"))
    }

    // --- continue ------------------------------------------------------------

    @Test
    fun continuesOnAFreshFollowUpUnderTheStepCeiling() {
        val last = call("search_notes", "query" to "trip")
        val proposal = Result.Success(call("summarize_text", "text" to "found note"))
        val decision = ReActLoopController.decide(stepsExecuted = 1, lastExecutedCall = last, proposal = proposal)
        assertTrue("expected Continue", decision is LoopDecision.Continue)
        assertEquals("summarize_text", (decision as LoopDecision.Continue).call.tool)
    }

    // --- stop: speak_response (natural end) ----------------------------------

    @Test
    fun stopsOnSpeakResponseAndExtractsTheText() {
        val last = call("summarize_text", "text" to "x")
        val proposal = Result.Success(speak("Here is the summary."))
        val decision = ReActLoopController.decide(1, last, proposal)
        assertTrue(decision is LoopDecision.Stop)
        assertEquals(StopReason.SPOKE_RESPONSE, (decision as LoopDecision.Stop).reason)
        assertEquals("Here is the summary.", decision.spokenText)
        assertNull(decision.plannerErrorText)
    }

    @Test
    fun speakResponseWithMissingTextFallsBackToDone() {
        val proposal = Result.Success(ToolCall("speak_response", JsonObject(emptyMap())))
        val decision = ReActLoopController.decide(1, null, proposal) as LoopDecision.Stop
        assertEquals(StopReason.SPOKE_RESPONSE, decision.reason)
        assertEquals("Done.", decision.spokenText)
    }

    // --- stop: no plan / planner error ---------------------------------------

    @Test
    fun stopsWithNoPlanWhenPlannerReturnedNull() {
        val decision = ReActLoopController.decide(1, call("search_notes"), proposal = null)
        assertEquals(StopReason.NO_PLAN, (decision as LoopDecision.Stop).reason)
    }

    @Test
    fun stopsWithPlannerErrorWhenPlannerRejectedItsOwnOutput() {
        // The brain rejects unknown tools / malformed args before returning; that surfaces as Error.
        val proposal = Result.Error("Rejected unknown tool: make_payment")
        val decision = ReActLoopController.decide(1, call("search_notes"), proposal) as LoopDecision.Stop
        assertEquals(StopReason.PLANNER_ERROR, decision.reason)
        assertEquals("Rejected unknown tool: make_payment", decision.plannerErrorText)
    }

    // --- stop: stall ---------------------------------------------------------

    @Test
    fun stopsOnStallWhenTheModelReproposesTheIdenticalCall() {
        val last = call("read_screen")
        val proposal = Result.Success(call("read_screen")) // same tool, same (empty) args
        val decision = ReActLoopController.decide(1, last, proposal) as LoopDecision.Stop
        assertEquals(StopReason.STALL_DETECTED, decision.reason)
    }

    @Test
    fun doesNotStallWhenSameToolButDifferentArgs() {
        // "search_notes x" then "search_notes y" is a legitimate new query, not a stall.
        val last = call("search_notes", "query" to "trip")
        val proposal = Result.Success(call("search_notes", "query" to "meeting"))
        val decision = ReActLoopController.decide(1, last, proposal)
        assertTrue("different args should Continue, not stall", decision is LoopDecision.Continue)
    }

    // --- stop: max steps -----------------------------------------------------

    @Test
    fun stopsAtMaxSteps() {
        val last = call("search_notes", "query" to "trip")
        val proposal = Result.Success(call("summarize_text", "text" to "x"))
        // MAX_STEPS includes the first call; once that many have executed, no more continuations.
        val decision = ReActLoopController.decide(
            stepsExecuted = ReActLoopController.MAX_STEPS,
            lastExecutedCall = last,
            proposal = proposal
        ) as LoopDecision.Stop
        assertEquals(StopReason.MAX_STEPS, decision.reason)
    }

    @Test
    fun continuesJustBelowMaxSteps() {
        val last = call("search_notes", "query" to "trip")
        val proposal = Result.Success(call("summarize_text", "text" to "x"))
        val decision = ReActLoopController.decide(
            stepsExecuted = ReActLoopController.MAX_STEPS - 1,
            lastExecutedCall = last,
            proposal = proposal
        )
        assertTrue("one step of headroom should Continue", decision is LoopDecision.Continue)
    }

    // --- precedence ----------------------------------------------------------

    @Test
    fun speakResponseBeatsMaxSteps() {
        // Even at the ceiling, a model that decides to speak should stop with SPOKE_RESPONSE, not
        // MAX_STEPS — the user hears the model's own words, not a generic "step limit" line.
        val proposal = Result.Success(speak("done"))
        val decision = ReActLoopController.decide(
            stepsExecuted = ReActLoopController.MAX_STEPS,
            lastExecutedCall = call("read_screen"),
            proposal = proposal
        ) as LoopDecision.Stop
        assertEquals(StopReason.SPOKE_RESPONSE, decision.reason)
    }
}