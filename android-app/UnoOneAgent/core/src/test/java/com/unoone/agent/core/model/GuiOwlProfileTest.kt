package com.unoone.agent.core.model

import org.junit.Assert.*
import org.junit.Test

class GuiOwlProfileTest {
    @Test fun identityConsentAndPreservation() {
        val owl = BrainModelRegistry.GUI_OWL_1_5_4B_INSTRUCT
        assertEquals(BrainRuntime.LLAMA_CPP, owl.runtime)
        assertEquals(ModelFamily.GUI_OWL_1_5, owl.modelFamily)
        assertFalse(owl.supportsBrowserProtocol)
        assertFalse(owl.isDeviceVerified)
        assertEquals(BrainExperimentalConsent.GUI_OWL, owl.experimentalConsent())
        assertEquals(BrainExperimentalConsent.QWEN, BrainModelRegistry.QWEN3_5_2B.experimentalConsent())
        assertEquals(BrainExperimentalConsent.NONE, BrainModelRegistry.GEMMA_4_E4B.experimentalConsent())
        assertEquals(BrainExperimentalConsent.NONE, BrainModelRegistry.GEMMA_4_E2B.experimentalConsent())
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrainModelRegistry.defaultProfile)
        BrainModelRegistry.all.forEach { assertEquals(it, BrainModelRegistry.resolveOrDefault(it.manifestId)) }
        assertEquals(2048, owl.maximumContextTokens)
        assertEquals(8192, owl.recommendedRamMb)
        assertTrue(GuiOwlArtifact.REQUIRED_AVAILABLE_BYTES > GuiOwlArtifact.DECODER_BYTES + GuiOwlArtifact.PROJECTOR_BYTES)
    }
}
