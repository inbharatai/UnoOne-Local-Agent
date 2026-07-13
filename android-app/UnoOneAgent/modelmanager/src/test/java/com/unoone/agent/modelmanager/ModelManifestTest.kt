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
          "manifestVersion": 2,
          "models": [
            {
              "id": "gemma-4-e2b",
              "folder": "brain/gemma-4-e2b",
              "type": "llm",
              "version": "gemma-4-E2B-it-litert-lm-main-6e5c4f1",
              "minRamMb": 6144,
              "backend": "any",
              "defaultLanguage": "en",
              "files": [
                {
                  "name": "gemma-4-E2B-it.litertlm",
                  "url": "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                  "sha256": "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                  "sizeBytes": 2588147712,
                  "archive": false
                }
              ]
            },
            {
              "id": "sherpa-asr-whisper",
              "folder": "speech/shared/sherpa-asr-whisper",
              "type": "asr",
              "version": "whisper-tiny-int8-baseline",
              "minRamMb": 512,
              "backend": "cpu",
              "defaultLanguage": "multi",
              "files": [
                {
                  "name": "sherpa-onnx-whisper-tiny.tar.bz2",
                  "url": "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
                  "sha256": "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
                  "sizeBytes": 116204861,
                  "archive": true,
                  "extractsTo": "sherpa-onnx-whisper-tiny"
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
    fun parsesCurrentGemmaAndSpeechModels() {
        val manifest = loader.parse(sample)
        assertEquals(2, manifest.manifestVersion)
        assertEquals(3, manifest.models.size)

        val gemma = manifest.find("gemma-4-e2b")
        assertNotNull(gemma)
        assertEquals(ModelType.llm, gemma!!.type)
        assertEquals(ModelBackend.any, gemma.backend)
        assertEquals("brain/gemma-4-e2b", gemma.folder)
        assertEquals(6144, gemma.minRamMb)
        assertEquals("gemma-4-E2B-it.litertlm", gemma.files.single().name)
        assertEquals(2588147712L, gemma.files.single().sizeBytes)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            gemma.files.single().sha256
        )

        val whisper = manifest.find("sherpa-asr-whisper")
        assertNotNull(whisper)
        assertTrue(whisper!!.files.single().archive)
        assertEquals("sherpa-onnx-whisper-tiny", whisper.files.single().extractsTo)

        val englishTts = manifest.find("sherpa-tts-en")
        assertNotNull(englishTts)
        assertEquals("speech/languages/en-IN/tts", englishTts!!.folder)
        assertEquals(2, englishTts.files.size)
        assertEquals("espeak-ng-data.zip", englishTts.files.first { it.asset != null }.asset)
    }

    @Test
    fun findByFolderUsesNormalizedV2Paths() {
        val manifest = loader.parse(sample)
        assertEquals("gemma-4-e2b", manifest.findByFolder("brain/gemma-4-e2b")!!.id)
        assertEquals("sherpa-asr-whisper", manifest.findByFolder("speech/shared/sherpa-asr-whisper")!!.id)
        assertEquals("sherpa-tts-en", manifest.findByFolder("speech/languages/en-IN/tts")!!.id)
    }

    @Test
    fun unknownKeysAreIgnoredWithoutChangingKnownFields() {
        val withUnknownField = sample.replace(
            "\"manifestVersion\": 2,",
            "\"manifestVersion\": 2, \"futureMetadata\": { \"ignored\": true },"
        )
        val manifest = loader.parse(withUnknownField)
        assertEquals(2, manifest.manifestVersion)
        assertEquals(3, manifest.models.size)
    }

    @Test
    fun parsesArchiveAndBundledAssetMetadata() {
        val manifest = loader.parse(sample)

        val whisperFile = manifest.find("sherpa-asr-whisper")!!.files.single()
        assertTrue(whisperFile.archive)
        assertEquals("sherpa-onnx-whisper-tiny", whisperFile.extractsTo)
        assertNull(whisperFile.asset)
        assertEquals(116204861L, whisperFile.sizeBytes)

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
