package com.unoone.agent.core.model

import org.junit.Assert.*
import org.junit.Test

class BrainModelRegistryTest {
    @Test fun `new selections default to E2B and persisted E4B stays E4B`() {
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrainModelRegistry.resolveOrDefault(null))
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrainModelRegistry.resolveOrDefault("unknown"))
        assertEquals(BrainModelRegistry.GEMMA_4_E4B, BrainModelRegistry.resolveOrDefault("gemma-4-e4b"))
        assertEquals(3, BrainModelRegistry.all.size)
        assertEquals(setOf("gemma-4-e2b", "gemma-4-e4b", "qwen3.5-2b-mnn"), BrainModelRegistry.all.map { it.manifestId }.toSet())
        BrainModelRegistry.all.forEach {
            assertEquals(it, BrainModelRegistry.byId(it.id))
            assertEquals(it, BrainModelRegistry.byFolder(it.modelFolder))
            assertEquals(it, BrainModelRegistry.byManifestId(it.manifestId))
            assertFalse(it.isDeviceVerified)
            assertFalse(it.isLegacy)
            if (it.runtime == BrainRuntime.MNN) return@forEach
            assertEquals(2048, E4bRuntimeBudgets.phone(it).contextTokens)
            assertEquals(96, E4bRuntimeBudgets.chat(it).outputTokens)
            assertEquals(384, E4bRuntimeBudgets.pageAgent(it).outputTokens)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `E2B cannot silently increase unqualified mobile context`() {
        E4bRuntimeBudgets.phone(BrainModelRegistry.GEMMA_4_E2B.copy(defaultContextTokens = 4096))
    }
}
