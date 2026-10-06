package com.unoone.agent.core.runtime

import com.unoone.agent.core.voice.*
import com.unoone.agent.core.latency.*
import org.junit.Assert.*
import org.junit.Test

class VoiceIngressTransportTest {
    @Test fun captureDuringQuestionACannotApproveQuestionBAtDispatch() {
        var clock = 100L
        val env = VoiceRouteEnvironment(true, 4, 900)
        val resolver = VoiceAppResolver { AppResolution.Resolved(AppChoice("com.example.maps", "Maps")) }
        fun purpose(id: String) = (VoiceRouteCompiler.route(VoiceIngress(id, "open Maps", 4, 1), env, resolver) as VoiceRoute.Native).purpose
        val gate = VoiceReviewGate()
        gate.publish(VoiceTaskReview("A", purpose("taskA"), 10, 1000))
        gate.markQuestionReady("A", 20)
        val recorder = LatencyRecorder { clock * 1_000_000 }.apply { enabled = true }
        val token = recorder.begin(LatencyOrigin.WAKE)
        val original = VoiceIngress("capture", "", 4, clock, UnderlyingAppEvidence("com.example.maps", 7, clock), "A")
        val capture = VoiceAdmissionTicket.captured(original, token)
        recorder.mark(token, LatencyStage.MIC_REQUEST)
        clock = 200
        gate.publish(VoiceTaskReview("B", purpose("taskB"), 150, 1000))
        gate.markQuestionReady("B", 160)
        val decoded = capture.copy(text = "confirm")
        var delivered: VoiceIngress? = null
        val callback: (VoiceAdmissionTicket) -> Unit = { delivered = it.toIngress() }
        callback(decoded)
        assertNull(gate.consumeSpeech(delivered!!, env))
        assertEquals("B", gate.pendingReview()?.reviewId)
        assertEquals(original.copy(transcript = "confirm"), delivered)
        assertSame(token, decoded.latencyToken)
        recorder.mark(decoded.latencyToken, LatencyStage.DECODE_END)
        assertNull(recorder.snapshots().single().outcome)
    }
    @Test fun legacyDefaultsNeverCarryApprovalAuthority() {
        val unknown = VoiceAdmissionTicket("confirm", 4, liveReviewId = "B")
        assertEquals("UNKNOWN", unknown.toIngress().requestId)
        assertEquals(0L, unknown.toIngress().captureStartMono)
        assertNull(unknown.toIngress().liveReviewId)
    }
}
