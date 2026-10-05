package com.unoone.agent.voice

import com.unoone.agent.modelmanager.ModelDescriptor
import com.unoone.agent.modelmanager.ModelType
import com.unoone.agent.voice.stt.SpeechModelIntegrity
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SpeechDescriptorRegressionTest {
    private val root = File("/models")
    private val canonical = ModelDescriptor("sherpa-asr-en", "speech/asr", ModelType.asr, "v1", files = emptyList())

    @Test fun sharedFolderAliasDoesNotRejectCanonicalAsr() {
        val models = listOf(canonical, canonical.copy(id = "language-pack-en-asr"))
        assertEquals(canonical, SpeechModelIntegrity.selectDescriptor(models, root, File(root, "speech/asr"), ModelType.asr, "sherpa-asr-en"))
    }

    @Test fun kwsFallbackAcceptsOnlyCanonicalVerifiedAsrIdentity() {
        val dir = File(root, "speech/asr")
        assertEquals(canonical, SpeechModelIntegrity.selectRuntimeDescriptor(listOf(canonical), root, dir, ModelType.kws, null))
        assertNull(SpeechModelIntegrity.selectRuntimeDescriptor(listOf(canonical.copy(id = "other-asr")), root, dir, ModelType.kws, null))
        assertNull(SpeechModelIntegrity.selectRuntimeDescriptor(listOf(canonical), root, File(root, "other"), ModelType.kws, null))
        assertNull(SpeechModelIntegrity.selectRuntimeDescriptor(listOf(canonical), root, dir, ModelType.tts, null))
        assertNull(SpeechModelIntegrity.selectRuntimeDescriptor(listOf(canonical, canonical), root, dir, ModelType.kws, null))
    }

    @Test fun wrongPathTypeAndDuplicateCanonicalIdFailClosed() {
        assertNull(SpeechModelIntegrity.selectDescriptor(listOf(canonical), root, File(root, "other"), ModelType.asr, "sherpa-asr-en"))
        assertNull(SpeechModelIntegrity.selectDescriptor(listOf(canonical), root, File(root, "speech/asr"), ModelType.tts, "sherpa-asr-en"))
        assertNull(SpeechModelIntegrity.selectDescriptor(listOf(canonical, canonical), root, File(root, "speech/asr"), ModelType.asr, "sherpa-asr-en"))
    }
}
