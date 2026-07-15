package com.unoone.agent.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the diagnostics sink. The stage/tool/STT/TTS counters are the orchestrator's
 * only observability surface for per-step latency; these pin the keys + the negative-clamp rule.
 * (Logger calls android.util.Log, which returns defaults under unitTests.isReturnDefaultValues.)
 */
class DiagnosticsTest {

    @Before
    fun resetMetrics() {
        Diagnostics.reset()
    }

    @Test
    fun recordStageStoresDurationUnderStageKey() {
        Diagnostics.recordStage("planning", 120L)
        assertEquals(120L, Diagnostics.getAllMetrics()["stage_planning_last_ms"])
    }

    @Test
    fun recordStageClampsNegativeDurationsToZero() {
        // A clock skew must never record a misleading negative stage duration.
        Diagnostics.recordStage("safety_judge", -50L)
        assertEquals(0L, Diagnostics.getAllMetrics()["stage_safety_judge_last_ms"])
    }

    @Test
    fun recordStageOverwritesLastValueOnRepeatedCalls() {
        Diagnostics.recordStage("command_total", 100L)
        Diagnostics.recordStage("command_total", 250L)
        assertEquals(250L, Diagnostics.getAllMetrics()["stage_command_total_last_ms"])
    }

    @Test
    fun recordToolExecutionStoresLastAndTotalAndActionCounter() {
        Diagnostics.recordToolExecution("open_calendar", 40L, true)
        val m = Diagnostics.getAllMetrics()
        assertEquals(40L, m["tool_open_calendar_last_ms"])
        assertEquals(40L, m["tool_open_calendar_total_ms"])
        assertEquals(1L, m["action_open_calendar_success"])
    }

    @Test
    fun recordToolExecutionAccumulatesTotalAcrossCalls() {
        Diagnostics.recordToolExecution("create_note", 10L, true)
        Diagnostics.recordToolExecution("create_note", 30L, false)
        assertEquals(40L, Diagnostics.getAllMetrics()["tool_create_note_total_ms"])
        assertEquals(1L, Diagnostics.getAllMetrics()["action_create_note_failure"])
    }

    @Test
    fun resetClearsAllMetrics() {
        Diagnostics.recordStage("planning", 100L)
        Diagnostics.recordToolExecution("open_chrome", 20L, true)
        Diagnostics.reset()
        assertTrue("reset must clear all metrics", Diagnostics.getAllMetrics().isEmpty())
    }
}