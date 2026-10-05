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
          "manifestVersion": 3,
          "models": [
            {
              "id": "gemma-4-e4b",
              "folder": "brain/gemma-4-e4b",
              "type": "llm",
              "version": "gemma-4-E4B-it-litert-lm-28299f30ee4d43294517a4ac93abd6163412f07f",
              "minRamMb": 8192,
              "backend": "any",
              "defaultLanguage": "en",
              "files": [
                {
                  "name": "gemma-4-E4B-it.litertlm",
                  "url": "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm",
                  "sha256": "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
                  "sizeBytes": 3659530240,
                  "archive": false
                }
              ]
            },
            {
              "id": "sherpa-asr-indic",
              "folder": "speech/shared/sherpa-asr-indic",
              "type": "asr",
              "version": "omnilingual-1600-languages-300M-ctc-int8-2025-11-12",
              "minRamMb": 2048,
              "backend": "cpu",
              "defaultLanguage": "multi",
              "files": [
                {
                  "name": "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
                  "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
                  "sha256": "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed",
                  "sizeBytes": 292571207,
                  "archive": true,
                  "extractsTo": "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
                }
              ]
            },
            {
              "id": "sherpa-tts-en",
              "folder": "speech/languages/en-IN/tts",
              "type": "tts",
              "version": "vits-coqui-en-ljspeech",
              "minRamMb": 512,
              "backend": "cpu",
              "defaultLanguage": "en",
              "files": [
                {
                  "name": "model.onnx",
                  "url": "https://huggingface.co/csukuangfj/vits-coqui-en-ljspeech/resolve/main/model.onnx",
                  "sha256": "3c4468add71e72431dec810545dcb44639b0b1fa117994d72dad0864f95ee9fd",
                  "sizeBytes": 114358757,
                  "archive": false
                },
                {
                  "name": "espeak-ng-data.zip",
                  "url": "",
                  "sha256": "289db3099d0b8d074d00578c277220410b0d89880481338f538c0d1bbdea673d",
                  "sizeBytes": 9014002,
                  "archive": true,
                  "asset": "espeak-ng-data.zip",
                  "extractsTo": "espeak-ng-data"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun shippedManifestPinsBothPlanningArtifacts() {
        val manifest = loader.parse(java.io.File("src/main/assets/models_manifest.json").readText())
        assertEquals(5, manifest.manifestVersion)
        assertEquals(3, manifest.models.count { it.type == ModelType.llm })
        val e2b = manifest.find("gemma-4-e2b")!!
        assertEquals("brain/gemma-4-e2b", e2b.folder)
        assertEquals("gemma-4-E2B-it.litertlm", e2b.files.single().name)
        assertEquals(2588147712L, e2b.files.single().sizeBytes)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", e2b.files.single().sha256)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm", e2b.files.single().url)
        val e4b = manifest.find("gemma-4-e4b")!!
        assertEquals(3659530240L, e4b.files.single().sizeBytes)
        assertEquals(E4bCleanupGate.SHA256, e4b.files.single().sha256)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm", e4b.files.single().url)
    }

    @Test
    fun parsesCurrentGemmaAndSpeechModels() {
        val manifest = loader.parse(sample)
        assertEquals(3, manifest.manifestVersion)
        assertEquals(3, manifest.models.size)

        val gemma = manifest.find("gemma-4-e4b")
        assertNotNull(gemma)
        assertEquals(ModelType.llm, gemma!!.type)
        assertEquals(ModelBackend.any, gemma.backend)
        assertEquals("brain/gemma-4-e4b", gemma.folder)
        assertEquals(8192, gemma.minRamMb)
        assertEquals("gemma-4-E4B-it.litertlm", gemma.files.single().name)
        assertEquals(3659530240L, gemma.files.single().sizeBytes)
        assertEquals(
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            gemma.files.single().sha256
        )
        assertTrue(gemma.files.single().url.contains("28299f30ee4d43294517a4ac93abd6163412f07f"))
        assertFalse(gemma.files.single().url.contains("/resolve/main/"))
        assertNull(manifest.find("gemma-4-e2b"))
        assertEquals(1, manifest.models.count { it.type == ModelType.llm })

        val indic = manifest.find("sherpa-asr-indic")
        assertNotNull(indic)
        assertTrue(indic!!.files.single().archive)
        assertEquals(
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            indic.files.single().extractsTo
        )

        val englishTts = manifest.find("sherpa-tts-en")
        assertNotNull(englishTts)
        assertEquals("speech/languages/en-IN/tts", englishTts!!.folder)
        assertEquals(2, englishTts.files.size)
        assertEquals("espeak-ng-data.zip", englishTts.files.first { it.asset != null }.asset)
    }

    @Test
    fun findByFolderUsesNormalizedV2Paths() {
        val manifest = loader.parse(sample)
        assertEquals("gemma-4-e4b", manifest.findByFolder("brain/gemma-4-e4b")!!.id)
        assertNull(manifest.findByFolder("brain/gemma-4-e2b"))
        assertEquals("sherpa-asr-indic", manifest.findByFolder("speech/shared/sherpa-asr-indic")!!.id)
        assertEquals("sherpa-tts-en", manifest.findByFolder("speech/languages/en-IN/tts")!!.id)
    }

    @Test
    fun unknownKeysAreIgnoredWithoutChangingKnownFields() {
        val withUnknownField = sample.replace(
            "\"manifestVersion\": 3,",
            "\"manifestVersion\": 3, \"futureMetadata\": { \"ignored\": true },"
        )
        val manifest = loader.parse(withUnknownField)
        assertEquals(3, manifest.manifestVersion)
        assertEquals(3, manifest.models.size)
    }

    @Test
    fun parsesArchiveAndBundledAssetMetadata() {
        val manifest = loader.parse(sample)

        val indicFile = manifest.find("sherpa-asr-indic")!!.files.single()
        assertTrue(indicFile.archive)
        assertEquals(
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            indicFile.extractsTo
        )
        assertNull(indicFile.asset)
        assertEquals(292571207L, indicFile.sizeBytes)

        val ttsFiles = manifest.find("sherpa-tts-en")!!.files
        val networkModel = ttsFiles.first { it.name == "model.onnx" }
        assertFalse(networkModel.archive)
        assertNull(networkModel.asset)

        val bundledData = ttsFiles.first { it.name == "espeak-ng-data.zip" }
        assertTrue(bundledData.archive)
        assertEquals("espeak-ng-data.zip", bundledData.asset)
        assertEquals("espeak-ng-data", bundledData.extractsTo)
        assertTrue(bundledData.url.isEmpty())
    }
}
