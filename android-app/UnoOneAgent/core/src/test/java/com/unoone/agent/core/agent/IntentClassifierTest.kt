package com.unoone.agent.core.agent

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the three-lane intent router. The router is the architectural fix for the
 * over-engineered command path: simple chats go to a one-inference chat lane (no agent flow), while
 * specific orders and anything ambiguous fall through to the agent pipeline. These tests pin the
 * conservative classification that keeps action orders out of the chat lane.
 */
class IntentClassifierTest {
    @Test
    fun romanizedHindiQuestionRoutesToChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("india kya hey", null))
        assertEquals(IntentType.CHAT, IntentClassifier.classify("bharat kya hai", null))
    }

    @Test
    fun supportedIndianScriptQuestionsRouteToChat() {
        val questions = listOf(
            "भारत क्या है",
            "ভারত কী",
            "இந்தியா என்ன",
            "భారతదేశం ఏమి",
            "ಭಾರತ ಏನು",
            "ഇന്ത്യ എന്ത്",
            "অসম ক'ত"
        )
        questions.forEach { text ->
            assertEquals(text, IntentType.CHAT, IntentClassifier.classify(text, null))
        }
    }

    private fun tc(tool: String): ToolCall = ToolCall(tool, JsonObject(emptyMap()))

    // === FAST_ACTION: rule match to a DIRECT (instant, no-confirm) tool ===

    @Test
    fun openCalendarRuleMatchIsFastAction() {
        assertEquals(IntentType.FAST_ACTION, IntentClassifier.classify("open calendar", tc("open_calendar")))
    }

    @Test
    fun createNoteRuleMatchIsFastAction() {
        assertEquals(IntentType.FAST_ACTION, IntentClassifier.classify("create note buy milk", tc("create_note")))
    }

    @Test
    fun checkCalendarRuleMatchIsFastAction() {
        assertEquals(IntentType.FAST_ACTION, IntentClassifier.classify("check my calendar", tc("check_calendar")))
    }

    @Test
    fun openChromeRuleMatchIsFastAction() {
        assertEquals(IntentType.FAST_ACTION, IntentClassifier.classify("open chrome", tc("open_chrome")))
    }

    @Test
    fun deactivateBlindAidRuleMatchIsFastAction() {
        assertEquals(
            IntentType.FAST_ACTION,
            IntentClassifier.classify("deactivate blind aid", tc("deactivate_blind_aid"))
        )
    }

    // === AGENT_ACTION: rule match to a CONFIRM/STRONG tool (no LLM, but needs a confirm tap) ===

    @Test
    fun openCameraRuleMatchIsAgentActionNotFast() {
        // open_camera is CONFIRM — not instant; it must not be labeled FAST_ACTION.
        assertEquals(IntentType.AGENT_ACTION, IntentClassifier.classify("open camera", tc("open_camera")))
    }

    @Test
    fun goBackRuleMatchIsAgentAction() {
        // system_control (go_back) is STRONG_CONFIRM.
        assertEquals(IntentType.AGENT_ACTION, IntentClassifier.classify("go back", tc("system_control")))
    }

    @Test
    fun detectObjectsRuleMatchIsAgentAction() {
        assertEquals(IntentType.AGENT_ACTION, IntentClassifier.classify("detect objects", tc("detect_objects")))
    }

    @Test
    fun deleteNotesRuleMatchIsAgentAction() {
        assertEquals(IntentType.AGENT_ACTION, IntentClassifier.classify("delete notes", tc("delete_notes")))
    }

    // === CHAT: question-shaped, no rule match, no action/screen intent ===

    @Test
    fun explainGodIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("explain god", null))
    }

    @Test
    fun whatIsPhotosynthesisIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("what is photosynthesis", null))
    }

    @Test
    fun tellMeAJokeIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("tell me a joke", null))
    }

    @Test
    fun whyIsSkyBlueIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("why is the sky blue?", null))
    }

    @Test
    fun describeTheSunsetIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("describe the sunset", null))
    }

    @Test
    fun whatTimeIsItIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("what time is it", null))
    }

    // === UNKNOWN: action intent but no rule match → safe default into the agent pipeline ===

    @Test
    fun findLoginButtonAndTapItIsUnknownNotChat() {
        // The audit's catcher requirement: a specific order with action verbs must reach the agent
        // even though it is question-free and has no rule match.
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("find the login button and tap it", null))
    }

    @Test
    fun readThisPageAndCreateNoteIsUnknownNotChat() {
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("read this page and create a note", null))
    }

    @Test
    fun whatsOnMyScreenIsUnknownNotChat() {
        // "screen" references the display → agent (needs screen context), not chat.
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("what's on my screen", null))
    }

    @Test
    fun remindMeToBuyMilkIsUnknownNotChat() {
        // "remind" is an action verb → agent.
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("remind me to buy milk", null))
    }

    @Test
    fun emptyInputWithNoRuleIsUnknown() {
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("", null))
    }

    @Test
    fun greetingWithNoQuestionSignalIsUnknown() {
        // "hello" is neither question-shaped nor an action → falls through to the agent pipeline,
        // which plans a speak_response. Not claimed as chat.
        assertEquals(IntentType.UNKNOWN, IntentClassifier.classify("hello", null))
    }

    @Test
    fun translateSentenceIsChat() {
        assertEquals(IntentType.CHAT, IntentClassifier.classify("translate this to hindi", null))
    }

    // === isDirectFastAction diagnostic helper ===

    @Test
    fun isDirectFastActionTrueForDirectRuleTool() {
        assert(IntentClassifier.isDirectFastAction(tc("open_calendar"))) { "open_calendar is DIRECT" }
    }

    @Test
    fun isDirectFastActionFalseForConfirmRuleTool() {
        assert(!IntentClassifier.isDirectFastAction(tc("open_camera"))) { "open_camera is CONFIRM" }
    }

    @Test
    fun isDirectFastActionFalseForNull() {
        assert(!IntentClassifier.isDirectFastAction(null)) { "no rule match" }
    }
}
