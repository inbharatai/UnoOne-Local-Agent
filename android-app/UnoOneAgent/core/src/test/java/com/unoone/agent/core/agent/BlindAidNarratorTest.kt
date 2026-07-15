package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for [BlindAidNarrator] — the eyes-free scene-summary throttle + wording. Pure logic; the
 * live camera detection + spoken output are device-time gates, not JVM-assertable.
 */
class BlindAidNarratorTest {

    @Test
    fun sceneSummaryListsDistinctLabelsWithArticles() {
        assertEquals(
            "In front of you: a chair, a desk, a person.",
            BlindAidNarrator.sceneSummary(listOf("Chair", "desk", "Person", "chair"))
        )
    }

    @Test
    fun sceneSummaryUsesAnBeforeVowels() {
        assertEquals("In front of you: an apple, an umbrella.", BlindAidNarrator.sceneSummary(listOf("apple", "umbrella")))
    }

    @Test
    fun sceneSummaryDropsGenericObstacleAndReturnsEmptyWhenOnlyObstacle() {
        assertEquals("", BlindAidNarrator.sceneSummary(listOf("Obstacle", "obstacle")))
        assertEquals("", BlindAidNarrator.sceneSummary(listOf("")))
        assertEquals("", BlindAidNarrator.sceneSummary(emptyList()))
    }

    @Test
    fun sceneSummaryIncludesObstacleAlongsideRealLabels() {
        // "obstacle" is dropped, real labels remain.
        assertEquals("In front of you: a chair.", BlindAidNarrator.sceneSummary(listOf("chair", "obstacle")))
    }

    @Test
    fun shouldNarrateIsSuppressedInQuietMode() {
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 10_000, lastNarrationMs = 0, lastLabels = emptySet(),
                currentLabels = setOf("chair"), quietMode = true
            )
        )
    }

    @Test
    fun shouldNarrateFiresImmediatelyOnLabelChangeAfterChangeInterval() {
        assertTrue(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 3_000, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("desk"), quietMode = false
            )
        )
    }

    @Test
    fun shouldNarrateRespectsChangeIntervalToAbsorbFlicker() {
        // Same "now", label set changed but only 1s since last narration (< 2s change interval).
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 1_000, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("desk"), quietMode = false
            )
        )
    }

    @Test
    fun shouldNarrateReFiresForSteadySceneAtSteadyInterval() {
        // Unchanged scene: not at 5s (< 6s steady), yes at 6s+.
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 5_000, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("chair"), quietMode = false
            )
        )
        assertTrue(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 6_500, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("chair"), quietMode = false
            )
        )
    }

    @Test
    fun shouldNarrateReturnsFalseForEmptyOrOnlyObstacleLabels() {
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 100_000, lastNarrationMs = 0, lastLabels = emptySet(),
                currentLabels = setOf("obstacle"), quietMode = false
            )
        )
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 100_000, lastNarrationMs = 0, lastLabels = emptySet(),
                currentLabels = emptySet(), quietMode = false
            )
        )
    }
}