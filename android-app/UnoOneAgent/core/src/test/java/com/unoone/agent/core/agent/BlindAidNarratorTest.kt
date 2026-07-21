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
    fun hindiSceneSummaryUsesNativeObjectNames() {
        assertEquals(
            "सामने: व्यक्ति, मोबाइल फोन, बोतल, टेलीविज़न।",
            BlindAidNarrator.sceneSummary(
                listOf("person", "cell phone", "bottle", "tv"),
                languageCode = "hi"
            )
        )
    }

    @Test
    fun hindiProximityWarningsAreShortAndNative() {
        assertEquals(
            "रुकिए। सामने कार है।",
            BlindAidNarrator.proximityWarning("car", immediate = true, languageCode = "hi-IN")
        )
        assertEquals(
            "कुर्सी सामने है।",
            BlindAidNarrator.proximityWarning("chair", immediate = false, languageCode = "hi")
        )
    }

    @Test
    fun hindiModeMessagesAvoidGenderedVoiceWording() {
        assertEquals("ब्लाइंड मोड बंद हो गया है।", BlindAidNarrator.deactivationMessage("hi"))
        assertTrue(
            BlindAidNarrator.activationMessage("hi")
                .startsWith("ब्लाइंड मोड चालू हो गया है।")
        )
        assertFalse(BlindAidNarrator.activationMessage("hi").contains("रही"))
        assertFalse(BlindAidNarrator.activationMessage("hi").contains("करूँगी"))
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
                nowMs = 10_500, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("desk"), quietMode = false
            )
        )
    }

    @Test
    fun shouldNarrateRespectsChangeIntervalToAbsorbFlicker() {
        // Label set changed but only 9s since last narration (< 10s change interval).
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 9_000, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("desk"), quietMode = false
            )
        )
    }

    @Test
    fun shouldNarrateReFiresForSteadySceneAtSteadyInterval() {
        // Unchanged scene: do not repeat a cached observation every few seconds; remind at 30s.
        assertFalse(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 29_000, lastNarrationMs = 0, lastLabels = setOf("chair"),
                currentLabels = setOf("chair"), quietMode = false
            )
        )
        assertTrue(
            BlindAidNarrator.shouldNarrateScene(
                nowMs = 30_500, lastNarrationMs = 0, lastLabels = setOf("chair"),
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
