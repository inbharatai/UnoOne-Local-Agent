package com.unoone.agent.core.latency

import org.junit.Assert.*
import org.junit.Test

class LatencyRecorderTest {
    @Test fun boundedAndPrivate() {
        var now = 0L
        val r = LatencyRecorder { now }
        assertNull(r.begin(LatencyOrigin.FLOATING))
        r.enabled = true
        repeat(300) {
            val t = r.begin(LatencyOrigin.FLOATING)
            r.bindRequest("secret transcript URL prompt task $it", t)
            repeat(140) { now += 1000; r.mark(t, LatencyStage.MIC_REQUEST) }
        }
        assertEquals(256, r.snapshots().size)
        assertTrue(r.snapshots().all { it.events.size == 128 && it.drops == 12 })
        val json = r.exportJson()
        assertTrue(json.contains("\"source\":\"ANDROID_MONOTONIC\""))
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("prompt"))
        r.enabled = false
        assertTrue(r.snapshots().isEmpty())
    }
    @Test fun independentClosuresAndConditionalQuantiles() {
        var now = 0L
        val r = LatencyRecorder { now }; r.enabled = true
        val a = r.begin(LatencyOrigin.FOREGROUND)
        val b = r.begin(LatencyOrigin.WAKE)
        r.mark(a, LatencyStage.DECODE_BEGIN)
        r.mark(b, LatencyStage.DECODE_BEGIN)
        now = 10_000
        r.mark(a, LatencyStage.DECODE_END)
        r.close(a, LatencyOutcome.COMPLETED_NON_ACTION)
        now = 20_000
        r.close(b, LatencyOutcome.TIMED_OUT)
        r.mark(a, LatencyStage.MODEL_BEGIN)
        r.begin(LatencyOrigin.FOREGROUND)
        val s = r.summary(LatencyStage.DECODE_BEGIN, LatencyStage.DECODE_END, LatencyPath.UNRESOLVED)
        assertEquals(1, s.samples); assertNull(s.p95Us)
        assertEquals(1, s.timeouts); assertEquals(1, s.censored)
        assertEquals(10L, r.snapshots().first().terminalUs)
        assertEquals(1, r.snapshots().first().invalid)
    }
    @Test fun acceptedReviewSurvivesProducerFinallyAndApprovalHasSeparateIdentity() {
        var now = 0L
        val r = LatencyRecorder { now }; r.enabled = true
        val task = r.begin(LatencyOrigin.FOREGROUND)
        r.bindRequest("private review A", task)
        r.acceptOwnership(task)
        r.mark(task, LatencyStage.APPROVAL_SHOWN)
        now = 1000; r.mark(task, LatencyStage.APPROVAL_READY)
        r.closeCapture(task)
        assertNull(r.snapshots().single().outcome)
        val approval = r.begin(LatencyOrigin.WAKE)
        r.acceptOwnership(approval)
        r.close(approval, LatencyOutcome.COMPLETED_NON_ACTION)
        now = 9000; r.mark(task, LatencyStage.APPROVAL_RESPONSE)
        r.close(task, LatencyOutcome.VERIFIED)
        assertEquals(9000L / 1000, r.snapshots().first().terminalUs)
        assertFalse(r.exportJson().contains("private review"))
        assertNull(r.forRequest("private review A"))
    }
    @Test fun independentReviewTimeoutAndStopCannotCloseAnotherTrace() {
        var now = 0L
        val r = LatencyRecorder { now }; r.enabled = true
        val a = r.begin(LatencyOrigin.FOREGROUND)
        val b = r.begin(LatencyOrigin.FOREGROUND)
        r.acceptOwnership(a); r.acceptOwnership(b)
        now = 60000000; r.close(a, LatencyOutcome.TIMED_OUT)
        r.closeCapture(b)
        assertNull(r.snapshots()[1].outcome)
        r.close(b, LatencyOutcome.CANCELLED)
        assertEquals(listOf(LatencyOutcome.TIMED_OUT, LatencyOutcome.CANCELLED), r.snapshots().map { it.outcome })
    }
}
