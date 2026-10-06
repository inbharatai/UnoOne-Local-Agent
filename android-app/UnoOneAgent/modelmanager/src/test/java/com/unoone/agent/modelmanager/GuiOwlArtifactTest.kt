package com.unoone.agent.modelmanager

import com.unoone.agent.core.model.GuiOwlArtifact
import org.junit.Assert.*
import org.junit.Test

class GuiOwlArtifactTest {
    @Test fun manifestRequiresExactPinnedPairAndPreservesProfiles() {
        val manifest = ModelManifestLoader().parse(java.io.File("src/main/assets/models_manifest.json").readText())
        val owl = manifest.find(GuiOwlArtifact.MANIFEST_ID)!!
        assertEquals(GuiOwlArtifact.FOLDER, owl.folder)
        assertEquals(listOf(
            ModelFile(GuiOwlArtifact.DECODER, GuiOwlArtifact.url(GuiOwlArtifact.DECODER), GuiOwlArtifact.DECODER_SHA256, GuiOwlArtifact.DECODER_BYTES),
            ModelFile(GuiOwlArtifact.PROJECTOR, GuiOwlArtifact.url(GuiOwlArtifact.PROJECTOR), GuiOwlArtifact.PROJECTOR_SHA256, GuiOwlArtifact.PROJECTOR_BYTES)
        ), owl.files)
        assertEquals(ModelBackend.cpu, owl.backend)
        listOf("gemma-4-e2b", "gemma-4-e4b", "qwen3.5-2b-mnn").forEach { assertNotNull(manifest.find(it)) }
    }
}
