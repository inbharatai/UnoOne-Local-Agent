package com.unoone.agent.phonecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectLabelEvidenceTest {
    @Test
    fun confirmsThreeRecentSightingsDespiteSingleFrameMisses() {
        val evidence = ObjectLabelEvidence(minimumHits = 3, windowMs = 1_500L)

        assertTrue(evidence.update(0L, setOf("person")).isEmpty())
        assertTrue(evidence.update(200L, emptySet()).isEmpty())
        assertTrue(evidence.update(400L, setOf("person")).isEmpty())
        assertTrue(evidence.update(600L, emptySet()).isEmpty())
        assertEquals(setOf("person"), evidence.update(800L, setOf("person")))
    }

    @Test
    fun expiresOldEvidenceAndNeverReturnsAbsentLabels() {
        val evidence = ObjectLabelEvidence(minimumHits = 3, windowMs = 1_000L)
        evidence.update(0L, setOf("tv"))
        evidence.update(100L, setOf("tv"))

        assertTrue(evidence.update(200L, emptySet()).isEmpty())
        assertTrue(evidence.update(1_500L, setOf("tv")).isEmpty())
    }
}
