package com.unoone.agent.voice

import com.unoone.agent.core.latency.*
import com.unoone.agent.core.voice.VoiceIngress
import org.junit.Assert.*
import org.junit.Test

class WakeCaptureTraceTest {
    @Test fun idle2000ChunksNeverEvictUsefulHistory() {
        var now = 1_000_000_000L
        val r = LatencyRecorder { now }; r.enabled = true
        val useful = r.begin(LatencyOrigin.FOREGROUND)
        r.close(useful, LatencyOutcome.VERIFIED)
        repeat(2000) {
            now += 500_000_000L
            val capture = WakeCaptureTrace.captured(VoiceIngress("request$it", "", 1, now / 1_000_000L))
            assertNull(capture.latencyToken)
        }
        assertEquals(1, r.snapshots().size)
        assertEquals(0L, r.evictionCount())
    }
    @Test fun admittedCaptureRetainsOriginalClockAndReview() {
        var now = 1_000_000_000L
        val r = LatencyRecorder { now }; r.enabled = true
        val capture = WakeCaptureTrace.captured(VoiceIngress("B", "", 1, 1000, liveReviewId = "A"))
        now += 2_000_000_000L
        val admitted = WakeCaptureTrace.admit(capture, 1, r)
        assertEquals(capture.copy(latencyToken = admitted.latencyToken), admitted)
        r.mark(admitted.latencyToken, LatencyStage.STT_SUBMIT)
        assertEquals(2_000_000L, r.snapshots().single().events.last().offsetUs)
        assertFalse(r.snapshots().single().events.any { it.stage == LatencyStage.MIC_RECORDING_CONFIRMED })
    }
    @Test fun staleGenerationCannotAllocateAndMissingStartIsNotZero() {
        val r = LatencyRecorder { 3_000_000_000L }; r.enabled = true
        val capture = WakeCaptureTrace.captured(VoiceIngress("B", "", 1, 1000))
        assertNull(WakeCaptureTrace.admit(capture, 2, r).latencyToken)
        assertNull(WakeCaptureTrace.admit(capture.copy(captureStartMono = 0), 1, r).latencyToken)
        assertTrue(r.snapshots().isEmpty())
        assertNull(r.beginAt(LatencyOrigin.WAKE, 4_000_000_000L))
    }
    @Test fun negativeExitsCloseOnlyUntransferredCapture() {
        val r = LatencyRecorder { 3_000_000_000L }; r.enabled = true
        val original = r.begin(LatencyOrigin.FOREGROUND); r.acceptOwnership(original)
        for (outcome in listOf(LatencyOutcome.REJECTED, LatencyOutcome.CANCELLED, LatencyOutcome.FAILED)) {
            val capture = WakeCaptureTrace.admit(WakeCaptureTrace.captured(VoiceIngress("B", "", 1, 1000)), 1, r)
            try { /* discarded STT / stale generation / decoder failure */ }
            finally { r.closeCapture(capture.latencyToken, outcome) }
            assertEquals(outcome, r.snapshots().last().outcome)
            assertNull(r.snapshots().first().outcome)
        }
        val transferred = r.begin(LatencyOrigin.WAKE)
        r.acceptOwnership(transferred); r.closeCapture(transferred, LatencyOutcome.REJECTED)
        assertNull(r.snapshots().last().outcome)
    }
}
