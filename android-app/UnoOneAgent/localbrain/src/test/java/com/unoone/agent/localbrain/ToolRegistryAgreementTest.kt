package com.unoone.agent.localbrain

import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces that what Gemma 4 is told it can call matches what UnoOne will accept.
 *
 * A tool advertised but absent from [CanonicalToolRegistry] would be rejected after wasting model
 * output. A canonical tool absent from the prompt would become an unreachable capability. The exact
 * bidirectional equality is therefore a safety and reliability invariant.
 */
class ToolRegistryAgreementTest {

    private fun advertisedNames(instruction: String): Set<String> =
        instruction.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").substringBefore('(').trim() }
            .filter { it.isNotBlank() }
            .toSet()

    @Test
    fun gemma4InstructionAdvertisesExactlyTheCanonicalTools() {
        val advertised = advertisedNames(PromptBuilder.buildSystemInstruction(ModelFamily.GEMMA_4))
        assertEquals(29, CanonicalToolRegistry.names.size)
        assertEquals(
            "Gemma 4 instruction must advertise exactly the canonical 29 tools (no more, no less)",
            CanonicalToolRegistry.names,
            advertised
        )
    }

    @Test
    fun gemma4InstructionAdvertisesNoNonCanonicalTool() {
        val advertised = advertisedNames(PromptBuilder.buildSystemInstruction(ModelFamily.GEMMA_4))
        assertTrue(
            "advertised-but-non-canonical tools: ${advertised - CanonicalToolRegistry.names}",
            CanonicalToolRegistry.names.containsAll(advertised)
        )
    }
}
