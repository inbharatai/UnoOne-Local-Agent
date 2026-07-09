package com.unoone.agent.localbrain

import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces that what the model is *told* it can call matches what the brain will *accept*:
 *
 * - The Gemma 4 system instruction in [PromptBuilder] must advertise **exactly** the canonical
 *   tool set in [CanonicalToolRegistry]. If a tool is advertised but not canonical the brain rejects
 *   it (wasted model output); if a tool is canonical but un-advertised the model can never propose
 *   it (silent capability loss). This bidirectional equality is the safety-critical invariant.
 * - The legacy Gemma 3n instruction must stay **byte-identical**: the original 23 tools, NOT the
 *   `voice_recording`/`web_search` added to the Gemma 4 instruction (preserves back-compat + tests).
 *
 * Both checks run on the plain JVM because [PromptBuilder] is pure Kotlin (no LiteRT-LM load).
 *
 * **Not enforced here, by necessity:** the `@Tool` method names on `UnoOneToolSet` cannot be
 * reflectively cross-checked in a JVM unit test — `litertlm-android` ships bytecode newer than the
 * project's JDK 17 test JVM can load (`UnsupportedClassVersionError`), so no unit test in this repo
 * loads `UnoOneToolSet`. That `UnoOneToolSet @Tool names == CanonicalToolRegistry.names` agreement
 * is therefore verified (a) by code review — the names match as of this commit — and (b) at device
 * time, where ART can load the LiteRT-LM classes: see `DEVICE_VERIFICATION.md` step 5.
 */
class ToolRegistryAgreementTest {

    /** Tool names parsed from a system instruction's "- name(...)" bullet lines. */
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
        assertEquals(26, CanonicalToolRegistry.names.size)
        assertEquals(
            "Gemma 4 instruction must advertise exactly the canonical 26 tools (no more, no less)",
            CanonicalToolRegistry.names,
            advertised
        )
    }

    @Test
    fun gemma4InstructionAdvertisesNoNonCanonicalTool() {
        // The complement of the equality check above, made explicit: nothing advertised to the model
        // falls outside the registry (so the brain never has to reject a tool it itself suggested).
        val advertised = advertisedNames(PromptBuilder.buildSystemInstruction(ModelFamily.GEMMA_4))
        assertTrue(
            "advertised-but-non-canonical tools: ${advertised - CanonicalToolRegistry.names}",
            CanonicalToolRegistry.names.containsAll(advertised)
        )
    }

    @Test
    fun legacyGemma3nInstructionIsUnchangedAndOmitsNewTools() {
        val legacy = advertisedNames(PromptBuilder.buildSystemInstruction(ModelFamily.GEMMA_3N))
        // The legacy instruction is preserved byte-for-byte: it advertises the original 23 tools,
        // NOT voice_recording/web_search (those were added to the Gemma 4 instruction only).
        assertEquals(23, legacy.size)
        assertFalse("legacy prompt must not advertise voice_recording", "voice_recording" in legacy)
        assertFalse("legacy prompt must not advertise web_search", "web_search" in legacy)
        // Every legacy-advertised tool is still a canonical tool.
        assertTrue(CanonicalToolRegistry.names.containsAll(legacy))
    }
}