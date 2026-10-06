package com.unoone.agent.core.latency

import org.junit.Assert.*
import org.junit.Test

class LatencyDisabledTest {
    @Test fun disabledOrAbsentTraceNeverReadsClock() {
        val recorder = LatencyRecorder { error("Disabled diagnostics touched the platform clock") }
        assertNull(recorder.begin(LatencyOrigin.FLOATING))
        assertNull(recorder.beginAt(LatencyOrigin.WAKE, 10))
        recorder.mark(null, LatencyStage.MIC_REQUEST)
        recorder.route(null, LatencyPath.NATIVE)
        recorder.closeCapture(null)
        recorder.close(null, LatencyOutcome.REJECTED)
        recorder.enabled = true
        recorder.mark(null, LatencyStage.MIC_REQUEST)
        assertTrue(recorder.snapshots().isEmpty())
    }
}
