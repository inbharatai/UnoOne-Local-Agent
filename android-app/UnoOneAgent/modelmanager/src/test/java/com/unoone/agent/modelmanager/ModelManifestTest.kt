package com.unoone.agent.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestTest {

    private val loader = ModelManifestLoader()

    private val sample = """
        {
          "manifestVersion": 1,
          "_comment": "ignored",
          "models": [
            {
              "id": "gemma-local", "folder": "gemma-local", "type": "llm",
              "version": "gemma-3n-e4b-litertlm", "minRamMb": 4096, "backend": "any",
              "defaultLanguage": "en",
              "files": [ { "name": "gemma-3n-e4b.litertlm", "url": "https://example.org/g.litertlm", "sha256": "", "sizeBytes": 0 } ]
            },
            {
              "id": "sherpa-tts", "folder": "sherpa-tts", "type": "tts",
              "version": "vits", "minRamMb": 512, "backend": "cpu", "defaultLanguage": "en",
              "files": [
                { "name": "model.onnx", "url": "https://example.org/m.onnx", "sha256": "abc", "sizeBytes": 100, "archive": false },
                { "name": "espeak-ng-data.zip", "url": "https://example.org/e.zip", "sha256": "", "sizeBytes": 0, "archive": true }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesAllModelsAndFiles() {
        val manifest = loader.parse(sample)
        assertEquals(1, manifest.manifestVersion)
        assertEquals(2, manifest.models.size)

        val llm = manifest.find("gemma-local")
        assertNotNull(llm)
        assertEquals(ModelType.llm, llm!!.type)
        assertEquals(ModelBackend.any, llm.backend)
        assertEquals(1, llm.files.size)
        assertTrue(llm.files.first().sha256.isEmpty())

        val tts = manifest.find("sherpa-tts")
        assertNotNull(tts)
        assertEquals(2, tts!!.files.size)
        assertTrue(tts.files.first { it.name == "espeak-ng-data.zip" }.archive)
        assertEquals(100L, tts.files.first { it.name == "model.onnx" }.sizeBytes)
    }

    @Test
    fun findByFolderWorks() {
        val manifest = loader.parse(sample)
        assertEquals("gemma-local", manifest.findByFolder("gemma-local")!!.id)
        assertNull(manifest.findByFolder("does-not-exist"))
    }

    @Test
    fun unknownKeysAreIgnored() {
        // "_comment" above must not break parsing; if it did, parse() would throw and the test fail.
        val manifest = loader.parse(sample)
        assertEquals(2, manifest.models.size)
    }
}