package com.unoone.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class E4bRuntimeBudgetTest {
    @Test
    fun `all E4B engine profiles enforce 2048 total tokens`() {
        val spec = BrainModelRegistry.GEMMA_4_E4B
        assertEquals(2_048, E4bRuntimeBudgets.phone(spec).contextTokens)
        assertEquals(2_048, E4bRuntimeBudgets.chat(spec).contextTokens)
        assertEquals(2_048, E4bRuntimeBudgets.pageAgent(spec).contextTokens)
    }

    @Test
    fun `output budgets are bounded below total context`() {
        val spec = BrainModelRegistry.GEMMA_4_E4B
        assertEquals(256, E4bRuntimeBudgets.phone(spec).outputTokens)
        assertEquals(96, E4bRuntimeBudgets.chat(spec).outputTokens)
        assertEquals(384, E4bRuntimeBudgets.pageAgent(spec).outputTokens)
    }
}
