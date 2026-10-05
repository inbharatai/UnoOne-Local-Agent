package com.unoone.agent.modelmanager

import org.junit.Assert.*
import org.junit.Test

class QwenMnnArtifactTest {
    @Test fun completePinnedExportMatchesShippedManifest() {
        val manifest = ModelManifestLoader().parse(java.io.File("src/main/assets/models_manifest.json").readText())
        val qwen = manifest.find(QwenMnnArtifact.MANIFEST_ID)!!
        assertEquals(QwenMnnArtifact.files, qwen.files)
        assertEquals(9, qwen.files.size)
        assertEquals(ModelBackend.cpu, qwen.backend)
        assertEquals("brain/qwen3.5-2b-mnn", qwen.folder)
        qwen.files.forEach {
            assertTrue(it.url.contains("/resolve/${QwenMnnArtifact.REVISION}/"))
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.sizeBytes > 0)
            assertFalse(it.archive)
        }
        assertNotNull(manifest.find("gemma-4-e4b"))
        assertNotNull(manifest.find("gemma-4-e2b"))
    }
}
