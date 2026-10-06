package com.unoone.agent

import com.unoone.agent.core.voice.*
import com.unoone.agent.core.device.*
import org.junit.Assert.*
import org.junit.Test

class VoicePurposeAdapterTest {
    private val env = VoiceRouteEnvironment(true, 4, 200)
    private val resolver = VoiceAppResolver { AppResolution.Resolved(AppChoice("com.example.notes", "Notes")) }
    private fun purpose(text: String) = (VoiceRouteCompiler.route(
        VoiceIngress("request", text, 4, 100, UnderlyingAppEvidence("com.example.notes", 7, 90)), env, resolver) as VoiceRoute.Native).purpose
    @Test fun lowercaseSpokenTargetPolicySurvivesReviewWithoutChangingValues() {
        val original = purpose("type \"AbC\" into \"search\"")
        val review = VoiceTaskReview("spoken", original, 200, 900)
        assertTrue(VoicePurposeAdapter.owlSupported(review.purpose))
        val before = VoicePurposeAdapter.goals(original)
        val after = VoicePurposeAdapter.goals(review.purpose)
        assertEquals(before, after)
        val goal = after.single() as NativeDeviceGoal.Interact
        assertEquals(NativeTargetMatchMode.CASE_INSENSITIVE_UNIQUE, goal.interaction.selector.matchMode)
        assertEquals("search", goal.interaction.selector.exactLabel)
        assertEquals("AbC", goal.interaction.exactValue)
        val tap = VoicePurposeAdapter.goals(purpose("tap search")).single() as NativeDeviceGoal.Interact
        assertEquals(NativeTargetMatchMode.CASE_INSENSITIVE_UNIQUE, tap.interaction.selector.matchMode)
    }

    @Test fun explicitDslKeepsExactTargetPolicy() {
        val goal = NativeDeviceCommands.parse("device: click \"search\"") as NativeDeviceGoal.Interact
        assertEquals(NativeTargetMatchMode.EXACT, goal.interaction.selector.matchMode)
        assertEquals("search", goal.interaction.selector.exactLabel)
    }

    @Test fun exactUnicodeWriteAndFrozenPackageSurviveAdapter() {
        val goal = VoicePurposeAdapter.goals(purpose("type \"Café THEN Ω\" into \"Search\"" )).single() as NativeDeviceGoal.Interact
        assertEquals("com.example.notes", goal.packageName)
        assertEquals("Café THEN Ω", goal.interaction.exactValue)
        assertEquals("Search", goal.interaction.selector.exactLabel)
        assertEquals(ReviewedOperation.WRITE, goal.interaction.operation)
    }
    @Test fun allSimpleGoalsRemainExplicitRatherThanCurrentForeground() {
        assertEquals(NativeDeviceGoal.Back("com.example.notes"), VoicePurposeAdapter.goals(purpose("go back")).single())
        assertEquals(NativeDeviceGoal.ReadScreen("com.example.notes"), VoicePurposeAdapter.goals(purpose("read screen")).single())
        assertEquals(NativeDeviceGoal.Scroll("com.example.notes", ScrollDirection.FORWARD), VoicePurposeAdapter.goals(purpose("scroll down")).single())
    }
    @Test fun owlNeverRescuesUnsupportedNativeOperation() {
        assertFalse(VoicePurposeAdapter.owlSupported(purpose("go back")))
        assertFalse(VoicePurposeAdapter.owlSupported(purpose("go home")))
        assertTrue(VoicePurposeAdapter.goals(purpose("go home")).single() is NativeDeviceGoal.NeedsUser)
    }
    @Test fun explicitSequenceOrderAndScopeArePreserved() {
        val goals = VoicePurposeAdapter.goals(purpose("open Notes then tap \"Search\" then type \"Hello\" into \"Search\""))
        assertTrue(goals[0] is NativeDeviceGoal.OpenApp)
        assertEquals(ReviewedOperation.CLICK, (goals[1] as NativeDeviceGoal.Interact).interaction.operation)
        assertEquals("Hello", (goals[2] as NativeDeviceGoal.Interact).interaction.exactValue)
    }
    @Test fun oldCaptureCannotApproveNewReviewWhileExecutionIsBusy() {
        val gate = VoiceReviewGate()
        val a = VoiceTaskReview("a", purpose("read screen"), 200, 900)
        gate.publish(a); gate.markQuestionReady("a", 210)
        val old = VoiceIngress("reply", "confirm", 4, 220, liveReviewId = "a")
        val b = VoiceTaskReview("b", purpose("tap Search"), 230, 900)
        gate.publish(b); gate.markQuestionReady("b", 240)
        assertNull(gate.consumeSpeech(old, env.copy(nowMono = 250)))
        assertNull(gate.consumeButton("a", "request", 4, ReviewDecision.CONFIRM, env.copy(nowMono = 250)))
        assertEquals("b", gate.pendingReview()?.reviewId)
    }
}
