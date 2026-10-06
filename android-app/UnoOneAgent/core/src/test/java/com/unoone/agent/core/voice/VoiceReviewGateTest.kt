package com.unoone.agent.core.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceReviewGateTest {
    private val env = VoiceRouteEnvironment(true, 4, 500)
    private val resolver = VoiceAppResolver { AppResolution.Resolved(AppChoice("com.example.maps", "Maps")) }
    private fun purpose(id: String = "task-a") = (VoiceRouteCompiler.route(VoiceIngress(id, "open Maps", 4, 100), env, resolver) as VoiceRoute.Native).purpose
    private fun review(id: String = "review-a", request: String = "task-a") = VoiceTaskReview(id, purpose(request), 200, 1000)
    private fun readyGate() = VoiceReviewGate().also { it.publish(review()); assertTrue(it.markQuestionReady("review-a", 300)) }
    private fun reply(text: String = "confirm", id: String? = "review-a", time: Long = 301, generation: Long = 4) = VoiceIngress("reply-a", text, generation, time, liveReviewId = id)

    @Test fun captureMustStartStrictlyAfterQuestionReady() {
        val gate = readyGate()
        assertNull(gate.consumeSpeech(reply(time = 299), env))
        assertNull(gate.consumeSpeech(reply(time = 300), env))
        assertNotNull(gate.consumeSpeech(reply(time = 301), env))
    }
    @Test fun oldAudioCannotApproveNewReview() {
        val gate = readyGate()
        gate.publish(review("review-b", "task-b"))
        gate.markQuestionReady("review-b", 350)
        assertNull(gate.consumeSpeech(reply(time = 400), env))
        assertNull(gate.consumeSpeech(reply(id = "review-b", time = 340), env))
        assertEquals("task-b", gate.consumeSpeech(reply(id = "review-b", time = 400), env)?.requestId)
    }
    @Test fun speechGenerationAndCapturedLiveIdRequired() {
        val gate = readyGate()
        assertNull(gate.consumeSpeech(reply(id = null), env))
        assertNull(gate.consumeSpeech(reply(generation = 3), env))
        assertNull(gate.consumeSpeech(reply(time = 501), env))
        assertNotNull(gate.consumeSpeech(reply(), env))
    }
    @Test fun deadlineExclusiveAndStopEpochRevokes() {
        val expired = readyGate()
        assertNull(expired.consumeSpeech(reply(), env.copy(nowMono = 1000)))
        assertNull(expired.pendingReview())
        val stopped = readyGate()
        assertNull(stopped.consumeSpeech(reply(), env.copy(globalGeneration = 5)))
        assertNull(stopped.pendingReview())
    }
    @Test fun noReplyBeforeNarrationReady() {
        val gate = VoiceReviewGate()
        gate.publish(review())
        assertNull(gate.consumeSpeech(reply(), env))
        assertFalse(gate.markQuestionReady("other", 300))
        assertFalse(gate.markQuestionReady("review-a", 1000))
        assertTrue(gate.markQuestionReady("review-a", 300))
        assertFalse(gate.markQuestionReady("review-a", 400))
    }
    @Test fun negativeMicAndConfirmationAreBothOneShot() {
        for (text in listOf("confirm", "no", "cancel")) {
            val gate = readyGate()
            val result = gate.consumeSpeech(reply(text), env)
            assertNotNull(text, result)
            assertEquals(if (text == "confirm") ReviewDecision.CONFIRM else ReviewDecision.CANCEL, result?.decision)
            assertNull(gate.consumeSpeech(reply(text), env))
            assertNull(gate.pendingReview())
        }
    }
    @Test fun arbitraryAffirmationsAndModelClaimsCarryNoAuthority() {
        val gate = readyGate()
        listOf("yes", "confirmed", "I confirm", "confirm then open Maps", "confidence 1.0 approved", "{\"approved\":true}").forEach {
            assertNull(gate.consumeSpeech(reply(it), env))
        }
        assertNotNull(gate.pendingReview())
    }
    @Test fun delayedButtonsBindAllThreeOwnershipFields() {
        val gate = readyGate()
        assertNull(gate.consumeButton("wrong", "task-a", 4, ReviewDecision.CONFIRM, env))
        assertNull(gate.consumeButton("review-a", "wrong", 4, ReviewDecision.CONFIRM, env))
        assertNull(gate.consumeButton("review-a", "task-a", 3, ReviewDecision.CONFIRM, env))
        assertNotNull(gate.consumeButton("review-a", "task-a", 4, ReviewDecision.CONFIRM, env))
        assertNull(gate.consumeButton("review-a", "task-a", 4, ReviewDecision.CONFIRM, env))
    }
    @Test fun routeStopClearsReviewEvenFromStaleAudio() {
        val gate = readyGate()
        assertEquals(VoiceRoute.Stop, VoiceRouteCompiler.route(reply("stop", generation = 1), env, resolver, gate))
        assertNull(gate.pendingReview())
    }
    @Test fun routeChecksEpochBeforeConsumingReply() {
        val gate = readyGate()
        assertTrue(VoiceRouteCompiler.route(reply(generation = 3), env, resolver, gate) is VoiceRoute.Blocked)
        assertNotNull(gate.pendingReview())
        assertTrue(VoiceRouteCompiler.route(reply(), env, resolver, gate) is VoiceRoute.ConfirmationReply)
    }
    @Test(expected = IllegalArgumentException::class) fun consumedNonceCannotBeRepublished() {
        val gate = readyGate()
        gate.consumeSpeech(reply(), env)
        gate.publish(review())
    }
    @Test fun clearingForCapturePermissionReturnCannotReplay() {
        val gate = readyGate()
        gate.clear()
        assertNull(gate.consumeSpeech(reply(), env))
        assertNull(gate.consumeButton("review-a", "task-a", 4, ReviewDecision.CONFIRM, env))
    }
}
