package com.unoone.agent

import com.unoone.agent.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Provider selection never changes the common execution schema boundary. */
class QwenSchemaRoutingTest {
    @Test fun `all three providers share strict schema rejection`() {
        assertEquals(setOf(BrainModelId.GEMMA_4_E2B, BrainModelId.GEMMA_4_E4B, BrainModelId.QWEN3_5_2B), BrainModelRegistry.all.map { it.id }.toSet())
        BrainModelRegistry.all.forEach { profile ->
            assertEquals(profile, BrainSelectionPolicy.resolve(profile.manifestId, false))
            for ((tool, args) in listOf("invented" to "{}", "read_screen" to "{\"url\":\"https://attacker.invalid\"}", "system_control" to "{\"action\":\"click\"}")) {
                assertNotNull(profile.manifestId, ToolCallValidator.rejection(ToolCall(tool, Json.parseToJsonElement(args).jsonObject)))
            }
            assertNull(ToolCallValidator.rejection(ToolCall("read_screen", buildJsonObject {})))
        }
    }

    @Test fun `runtime identity cannot route Qwen as LiteRT and does not change defaults`() {
        assertEquals(BrainRuntime.MNN, BrainModelRegistry.QWEN3_5_2B.runtime)
        assertEquals(BackendPreference.CPU_ONLY, BrainModelRegistry.QWEN3_5_2B.preferredBackend)
        assertEquals("config.json", BrainModelRegistry.QWEN3_5_2B.fileName)
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrainSelectionPolicy.resolve(null, false))
        assertEquals(BrainModelRegistry.GEMMA_4_E4B, BrainSelectionPolicy.resolve("gemma-4-e4b", false))
        listOf(BrainModelRegistry.GEMMA_4_E2B, BrainModelRegistry.GEMMA_4_E4B).forEach {
            assertEquals(BrainRuntime.LITERT_LM, it.runtime)
        }
    }
}
