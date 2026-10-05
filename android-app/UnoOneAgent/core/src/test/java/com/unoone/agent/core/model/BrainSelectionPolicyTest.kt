package com.unoone.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BrainSelectionPolicyTest {
    private val e2b = BrainModelRegistry.GEMMA_4_E2B
    private val e4b = BrainModelRegistry.GEMMA_4_E4B

    @Test fun `fresh install defaults to E2B`() {
        assertEquals(e2b, BrainSelectionPolicy.resolve(null, false))
    }

    @Test fun `legacy verified installed E4B is preserved on first migration`() {
        assertEquals(e4b, BrainSelectionPolicy.resolve(null, true))
    }

    @Test fun `no selection and no verified legacy artifact chooses E2B`() {
        // Unverified, corrupt, partial and missing legacy files all count as not installed.
        assertEquals(e2b, BrainSelectionPolicy.resolve(null, false))
    }

    @Test fun `saved E4B is preserved whether installed or missing`() {
        listOf(false, true).forEach {
            assertEquals(e4b, BrainSelectionPolicy.resolve(e4b.manifestId, it))
        }
    }

    @Test fun `saved E2B remains selected even with installed E4B`() {
        assertEquals(e2b, BrainSelectionPolicy.resolve(e2b.manifestId, true))
    }

    @Test fun `missing selected E2B never silently falls back to installed E4B`() {
        // Selection is independent of selected-profile installation/load success.
        val persistedChoice = BrainSelectionPolicy.resolve(e2b.manifestId, true)
        assertEquals(e2b, persistedChoice)
        assertEquals(e2b, BrainSelectionPolicy.resolve(persistedChoice.manifestId, true))
    }

    @Test fun `migration is not repeated after default was persisted`() {
        val migrated = BrainSelectionPolicy.resolve(null, false)
        assertEquals(e2b, BrainSelectionPolicy.resolve(migrated.manifestId, true))
    }

    @Test fun `unknown saved selection uses registry default not legacy migration`() {
        assertEquals(e2b, BrainSelectionPolicy.resolve("unknown", true))
    }
}
