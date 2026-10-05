package com.unoone.agent.voice.stt

import android.content.Context
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.modelmanager.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/** Synchronous initializers retain their API; callers must initialize off the UI thread. */
internal object SpeechModelIntegrity {
    internal fun selectDescriptor(
        models: List<com.unoone.agent.modelmanager.ModelDescriptor>,
        root: File, dir: File, type: ModelType, expectedId: String?
    ) = models.singleOrNull {
        it.type == type && (expectedId == null || it.id == expectedId) &&
            File(root, it.folder).canonicalFile == dir.canonicalFile
    }

    internal fun selectRuntimeDescriptor(
        models: List<com.unoone.agent.modelmanager.ModelDescriptor>, root: File, dir: File,
        type: ModelType, expectedId: String?
    ) = selectDescriptor(models, root, dir, type, expectedId)
        ?: (if (type == ModelType.kws && expectedId == null)
            selectDescriptor(models, root, dir, ModelType.asr, "sherpa-asr-en") else null)

    fun requireVerified(context: Context, modelDir: String, type: ModelType, mode: SttMode? = null) {
        val manager = ModelManager(context)
        val root = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        val dir = File(modelDir).canonicalFile
        // Language-pack aliases share ASR folders; select the canonical engine ID first.
        val expectedId = when (mode) {
            SttMode.TRANSDUCER -> "sherpa-asr-en"
            SttMode.OMNILINGUAL -> "sherpa-asr-indic"
            SttMode.WHISPER -> error("No verified Whisper descriptor installed")
            null -> null
        }
        val models = manager.loadManifest().models
        // Only the exact manifest-bound English transducer is an approved KWS fallback.
        val descriptor = selectRuntimeDescriptor(models, root, dir, type, expectedId)
            ?: error("Speech folder is not bound to the installed manifest")
        require(descriptor.files.isNotEmpty())
        // Fresh rehash: a cached stat-based verification must not authorize same-size corruption.
        descriptor.files.filterNot { it.archive }.forEach { entry ->
            val file = File(dir, entry.name)
            require(file.canonicalFile.toPath().startsWith(dir.toPath()))
            require(entry.sizeBytes > 0 && file.length() == entry.sizeBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }.equals(entry.sha256, true))
        }
        // Includes archive receipt identity and every extracted file (e.g. espeak tree).
        val health = runBlocking(Dispatchers.IO) { manager.modelHealth(descriptor.id) }
        require(health.healthy && health.verified) { "Speech integrity rejected: ${health.message}" }
    }
}
