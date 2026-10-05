package com.unoone.agent.core.model

import org.junit.Assert.*
import org.junit.Test

class QwenRuntimePolicyTest {
    @Test fun `qwen is explicit experimental MNN and never changes saved Gemma selection`() {
        val qwen = BrainModelRegistry.QWEN3_5_2B
        assertEquals(BrainRuntime.MNN, qwen.runtime)
        assertEquals(ModelFamily.QWEN3_5, qwen.modelFamily)
        assertFalse(qwen.isDeviceVerified)
        assertTrue(qwen.experimentalLabel!!.contains("EXPERIMENTAL"))
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrainModelRegistry.defaultProfile)
        assertEquals(BrainModelRegistry.GEMMA_4_E4B, BrainSelectionPolicy.resolve("gemma-4-e4b", false))
        assertEquals(qwen, BrainSelectionPolicy.resolve("qwen3.5-2b-mnn", false))
    }

    @Test fun `policy records bounded nonthinking CPU overrides`() {
        val receipt = QwenRuntimePolicy().receipt()
        assertEquals(2048, receipt.contextTokens)
        assertEquals(256, receipt.outputTokens)
        assertFalse(receipt.thinkingEnabled)
        assertEquals("cpu", receipt.backend)
        assertTrue(receipt.resolvedOverrideJson.contains("\"enable_thinking\":false"))
        assertEquals(4096, QwenRuntimePolicy(contextTokens = 4096).contextTokens)
    }

    @Test fun `all Gemma budget entry points reject Qwen and zero output is invalid`() {
        val qwen = BrainModelRegistry.QWEN3_5_2B
        listOf<() -> Unit>({ E4bRuntimeBudgets.phone(qwen) }, { E4bRuntimeBudgets.chat(qwen) }, { E4bRuntimeBudgets.pageAgent(qwen) }, { QwenRuntimePolicy(outputTokens = 0) }).forEach { action ->
            try { action(); fail("must reject invalid runtime or budget") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded output rejected`() { QwenRuntimePolicy(outputTokens = 8192) }

    @Test(expected = IllegalArgumentException::class)
    fun `unbounded context rejected`() { QwenRuntimePolicy(contextTokens = 8192) }

    @Test(expected = IllegalArgumentException::class)
    fun `Gemma budgets reject MNN profile`() { E4bRuntimeBudgets.pageAgent(BrainModelRegistry.QWEN3_5_2B) }
}
