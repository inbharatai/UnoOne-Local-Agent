package com.unoone.agent

import com.unoone.agent.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Three generic providers share tool schema; Owl has a separate mobile_use protocol. */
class QwenSchemaRoutingTest {
    @Test fun `all three providers share strict schema rejection`() {
        assertEquals(setOf(BrainModelId.GEMMA_4_E2B, BrainModelId.GEMMA_4_E4B, BrainModelId.QWEN3_5_2B), BrainModelRegistry.all.filter { it.id != BrainModelId.GUI_OWL_1_5_4B_INSTRUCT }.map { it.id }.toSet())
        BrainModelRegistry.all.filter { it.id != BrainModelId.GUI_OWL_1_5_4B_INSTRUCT }.forEach { profile ->
            assertEquals(profile, BrainSelectionPolicy.resolve(profile.manifestId, false))
            for ((tool, args) in listOf("invented" to "{}", "read_screen" to "{\"url\":\"https://attacker.invalid\"}", "system_control" to "{\"action\":\"click\"}")) {
                assertNotNull(profile.manifestId, ToolCallValidator.rejection(ToolCall(tool, Json.parseToJsonElement(args).jsonObject)))
            }
            assertNull(ToolCallValidator.rejection(ToolCall("read_screen", buildJsonObject {})))
        }
    }

    @Test fun `Owl is a separate fourth provider with mobile use protocol`() {
        assertEquals(4, BrainModelRegistry.all.size)
        assertTrue(BrainModelRegistry.all.contains(BrainModelRegistry.GUI_OWL_1_5_4B_INSTRUCT))
        val raw = "Action: Done.\n" + """<tool_call>{"name":"mobile_use","arguments":{"action":"terminate","status":"success"}}</tool_call>"""
        assertEquals("terminate", com.unoone.agent.core.guiowl.OwlOutputCodec.decode(raw).action)
        assertNotNull(ToolCallValidator.rejection(ToolCall("mobile_use", buildJsonObject { put("action", "terminate") })))
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
