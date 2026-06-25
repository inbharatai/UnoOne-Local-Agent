package com.unoone.agent.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun parsesExtractsToAndAssetForPerLanguageModels() {
        // Mirrors the shipped per-language manifest: a whisper tarball entry (archive + extractsTo)
        // and an MMS TTS entry (plain files). Verifies the new ModelFile fields round-trip.
        val json = """
            {
              "manifestVersion": 1,
              "models": [
                {
                  "id": "sherpa-asr-whisper", "folder": "sherpa-asr-whisper", "type": "asr",
                  "version": "whisper-tiny-int8", "minRamMb": 512, "backend": "cpu",
                  "defaultLanguage": "multi",
                  "files": [
                    { "name": "sherpa-onnx-whisper-tiny.tar.bz2",
                      "url": "https://github.com/k2-fsa/sherpa-onnx/sherpa-onnx-whisper-tiny.tar.bz2",
                      "sha256": "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
                      "sizeBytes": 116204861, "archive": true,
                      "extractsTo": "sherpa-onnx-whisper-tiny" }
                  ]
                },
                {
                  "id": "sherpa-tts-hin", "folder": "sherpa-tts-hin", "type": "tts",
                  "version": "mms-vits-hin", "minRamMb": 512, "backend": "cpu", "defaultLanguage": "hi",
                  "files": [
                    { "name": "model.onnx",
                      "url": "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/hin/model.onnx",
                      "sha256": "42c69b3611dc016ff337e994c78a76b5131156718c5a69e9cfa8912cfd850c5e",
                      "sizeBytes": 114043064, "archive": false }
                  ]
                },
                {
                  "id": "sherpa-tts-en", "folder": "sherpa-tts-en", "type": "tts",
                  "version": "vits-coqui-en-ljspeech", "minRamMb": 512, "backend": "cpu",
                  "defaultLanguage": "en",
                  "files": [
                    { "name": "espeak-ng-data.zip", "url": "", "sha256": "abc", "sizeBytes": 9014002,
                      "archive": true, "asset": "espeak-ng-data.zip", "extractsTo": "espeak-ng-data" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val manifest = loader.parse(json)
        assertEquals(3, manifest.models.size)

        val whisper = manifest.find("sherpa-asr-whisper")!!
        val wf = whisper.files.single()
        assertTrue(wf.archive)
        assertEquals("sherpa-onnx-whisper-tiny", wf.extractsTo)
        assertEquals(116204861L, wf.sizeBytes)
        assertNull(wf.asset)

        val hin = manifest.find("sherpa-tts-hin")!!
        assertEquals("hi", hin.defaultLanguage)
        assertEquals(114043064L, hin.files.first().sizeBytes)
        assertFalse(hin.files.first().archive)

        val en = manifest.find("sherpa-tts-en")!!
        val ef = en.files.first()
        assertEquals("espeak-ng-data.zip", ef.asset)
        assertEquals("espeak-ng-data", ef.extractsTo)
    }
}