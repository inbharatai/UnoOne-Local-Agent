package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for self-heal: the rolling tool-health tracker (flaky detection, window eviction) and
 * the brain-reload threshold. The actual reload + spoken diagnostic are device-time-only; these pin the
 * decision contract that triggers them.
 */
class ToolHealthTrackerTest {

    @Test
    fun toolNotFlakyBelowThreshold() {
        val t = ToolHealthTracker(windowSize = 5, flakyThreshold = 3)
        repeat(2) { t.record("open_app", success = false) }
        assertFalse(t.isFlaky("open_app"))
        assertEquals(2, t.failureCount("open_app"))
    }

    @Test
    fun toolFlakyAtThreshold() {
        val t = ToolHealthTracker(windowSize = 5, flakyThreshold = 3)
        repeat(3) { t.record("open_app", success = false) }
        assertTrue(t.isFlaky("open_app"))
    }

    @Test
    fun successesDiluteButWindowEvictsOldFailures() {
        val t = ToolHealthTracker(windowSize = 3, flakyThreshold = 3)
        // 3 failures → flaky
        repeat(3) { t.record("ocr_screen", success = false) }
        assertTrue(t.isFlaky("ocr_screen"))
        // 3 successes push the 3 failures out of the window → no longer flaky
        repeat(3) { t.record("ocr_screen", success = true) }
        assertFalse("old failures evicted, no longer flaky", t.isFlaky("ocr_screen"))
    }

    @Test
    fun toolsTrackedIndependently() {
        val t = ToolHealthTracker(windowSize = 5, flakyThreshold = 2)
        t.record("open_app", success = false)
        t.record("open_app", success = false)
        t.record("open_chrome", success = true)
        assertTrue(t.isFlaky("open_app"))
        assertFalse(t.isFlaky("open_chrome"))
    }

    @Test
    fun resetClearsWindow() {
        val t = ToolHealthTracker(windowSize = 5, flakyThreshold = 2)
        repeat(3) { t.record("open_app", success = false) }
        t.reset("open_app")
        assertEquals(0, t.failureCount("open_app"))
        assertFalse(t.isFlaky("open_app"))
    }

    @Test
    fun unknownToolHasZeroFailures() {
        val t = ToolHealthTracker()
        assertEquals(0, t.failureCount("never_called"))
        assertFalse(t.isFlaky("never_called"))
    }

    @Test
    fun brainReloadFiresAtThreshold() {
        assertFalse(BrainHealthPolicy.shouldReload(0))
        assertFalse(BrainHealthPolicy.shouldReload(1))
        assertTrue(BrainHealthPolicy.shouldReload(BrainHealthPolicy.RELOAD_THRESHOLD))
        assertTrue(BrainHealthPolicy.shouldReload(99))
    }
}