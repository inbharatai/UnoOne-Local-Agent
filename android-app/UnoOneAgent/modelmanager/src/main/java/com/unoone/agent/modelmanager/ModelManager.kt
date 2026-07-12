package com.unoone.agent.modelmanager

import android.content.Context
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ModelMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Source-of-truth model filesystem facade.
 *
 * UnoOne V2 stores models below the app-private `files/models` root using typed subdirectories:
 * `brain/`, `speech/`, `vision/`, `ocr/` and `staging/`. Manifest descriptors determine install,
 * health and uninstall paths; the fallback list exists only for a damaged/missing bundled manifest
 * and mirrors that same V2 layout. No Gemma 3n or `gemma-local` compatibility path is retained.
 */
class ModelManager(
    private val context: Context,
    private val modelMetadataDao: ModelMetadataDao? = null
) {

    private val manifestLoader = ModelManifestLoader()
    private val installer: ModelInstaller by lazy {
        ModelInstaller(appPrivateModelPath, modelMetadataDao) { name ->
            runCatching { context.assets.open(name) }.getOrNull()
        }
    }

    private val appPrivateModelPath: String
        get() = context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.resolve("models").absolutePath

    fun loadManifest(): ModelManifest = manifestLoader.load(context)

    fun findModel(id: String): ModelDescriptor? = manifestLoader.find(context, id)

    /**
     * Verifies every declared file. A present, non-empty file with no declared SHA-256 or size is
     * healthy but unverified; this is how an imported Gemma candidate is represented until its exact
     * production artifact is qualified and the manifest is updated.
     */
    suspend fun modelHealth(id: String): HealthResult = withContext(Dispatchers.IO) {
        val descriptor = findModel(id)
            ?: return@withContext HealthResult(
                modelId = id,
                healthy = false,
                verified = false,
                missing = emptyList(),
                sizeMismatch = emptyList(),
                checksumMismatch = emptyList(),
                unverified = emptyList(),
                message = "Unknown model id"
            )

        val folder = File(appPrivateModelPath, descriptor.folder)
        val missing = mutableListOf<String>()
        val sizeMismatch = mutableListOf<String>()
        val checksumMismatch = mutableListOf<String>()
        val unverified = mutableListOf<String>()

        for (file in descriptor.files) {
            if (file.archive) {
                val extractedName = file.extractsTo?.takeIf { it.isNotBlank() }
                    ?: file.name.substringBeforeLast('.')
                val extracted = File(folder, extractedName)
                if (!extracted.exists() || !extracted.isDirectory || extracted.listFiles().isNullOrEmpty()) {
                    missing += file.name
                }
                continue
            }

            val target = File(folder, file.name)
            if (!target.exists() || target.length() == 0L) {
                missing += file.name
                continue
            }
            if (file.sizeBytes == 0L && file.sha256.isBlank()) {
                unverified += file.name
                continue
            }
            if (file.sizeBytes > 0 && target.length() != file.sizeBytes) sizeMismatch += file.name
            if (file.sha256.isNotBlank() && computeSha256(target.absolutePath) != file.sha256.lowercase()) {
                checksumMismatch += file.name
            }
        }

        val healthy = missing.isEmpty() && sizeMismatch.isEmpty() && checksumMismatch.isEmpty()
        val verified = healthy && unverified.isEmpty()
        val message = when {
            !healthy -> "Needs repair (missing/size/hash mismatch)"
            unverified.isNotEmpty() -> "Present — not hash-verified; qualify the exact artifact before release"
            else -> "Verified"
        }
        HealthResult(
            modelId = id,
            healthy = healthy,
            verified = verified,
            missing = missing,
            sizeMismatch = sizeMismatch,
            checksumMismatch = checksumMismatch,
            unverified = unverified,
            message = message
        )
    }

    suspend fun installModel(
        id: String,
        onProgress: ((
            modelId: String,
            fileIndex: Int,
            totalFiles: Int,
            file: String,
            downloaded: Long,
            total: Long
        ) -> Unit)? = null
    ): ModelInstaller.InstallResult {
        val descriptor = findModel(id)
            ?: return ModelInstaller.InstallResult.Failure("Unknown model id: $id")
        return installer.install(
            descriptor,
            onProgress?.let { callback ->
                ModelInstaller.ProgressListener { mid, fileIndex, totalFiles, file, downloaded, total ->
                    callback(mid, fileIndex, totalFiles, file, downloaded, total)
                }
            }
        )
    }

    /** Deletes only the manifest-resolved folder beneath the app-private models root. */
    suspend fun uninstallModel(id: String) = withContext(Dispatchers.IO) {
        val descriptor = findModel(id)
        val base = File(appPrivateModelPath)
        val folder = if (descriptor != null) File(base, descriptor.folder) else File(base, id)
        val baseCanonical = base.canonicalPath
        val folderCanonical = folder.canonicalPath
        if (folderCanonical != baseCanonical && !folderCanonical.startsWith(baseCanonical + File.separator)) {
            Logger.w("ModelManager: refusing to uninstall '$id' outside models root ($folderCanonical)")
            return@withContext
        }
        if (folder.exists()) {
            folder.walkTopDown().sortedByDescending { it.path }.forEach { runCatching { it.delete() } }
            runCatching { folder.delete() }
        }
        modelMetadataDao?.deleteByName(id)
        Logger.i("ModelManager: uninstalled $id")
    }

    suspend fun detectModels(): List<ModelStatus> = withContext(Dispatchers.IO) {
        val base = File(appPrivateModelPath)
        if (!base.exists()) base.mkdirs()

        val manifest = loadManifest()
        val known: List<Pair<String, String>> = manifest.models.map { it.folder to it.type.name }
            .ifEmpty { FALLBACK_MODEL_FOLDERS }

        known.map { (folderName, type) ->
            val folder = File(base, folderName)
            val hasRealFile = folder.walkTopDown().any { file ->
                file.isFile && !file.name.endsWith(".part") && file.length() > 0L
            }
            val present = folder.exists() && folder.isDirectory && hasRealFile
            val sizeMb = if (present) {
                folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            } else 0L
            val descriptor = manifest.findByFolder(folderName)
            val health = if (descriptor != null && present) modelHealth(descriptor.id) else null
            ModelStatus(
                name = folderName,
                type = type,
                present = present,
                loaded = false,
                sizeMb = sizeMb,
                version = descriptor?.version.orEmpty(),
                expectedSha256 = descriptor?.files?.firstOrNull()?.sha256.orEmpty(),
                healthy = health?.healthy ?: false,
                verified = health?.verified ?: false
            )
        }.also { models ->
            Logger.d("Detected ${models.count { it.present }} models present out of ${models.size}")
        }
    }

    suspend fun verifyChecksum(path: String, expected: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        file.exists() && computeSha256(path) == expected.lowercase()
    }

    private fun computeSha256(path: String): String? = try {
        val file = File(path)
        if (!file.exists()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Logger.e("Checksum computation failed for $path", e)
        null
    }

    fun getStorageUsageMb(): Long {
        val base = File(appPrivateModelPath)
        if (!base.exists()) return 0L
        return base.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }

    fun getModelFolderPath(modelName: String): String =
        File(appPrivateModelPath, modelName).absolutePath

    /** Creates all manifest folders plus non-manifest V2 runtime directories. */
    fun ensureModelDirectories() {
        val base = File(appPrivateModelPath)
        val manifestFolders = loadManifest().models.map { it.folder }.ifEmpty {
            FALLBACK_MODEL_FOLDERS.map { it.first }
        }
        (manifestFolders + RUNTIME_DIRECTORIES).distinct().forEach { File(base, it).mkdirs() }
    }

    /** Returns the sole Gemma 4 E2B candidate path, or null when no artifact is installed. */
    fun getLlmModelPath(): String? = getLlmModelPath(BrainModelRegistry.defaultProfile)

    fun getLlmModelPath(spec: BrainModelSpec): String? {
        val folder = File(appPrivateModelPath, spec.modelFolder)
        val candidates = folder.listFiles { file ->
            file.isFile && file.name.endsWith(spec.fileExtension, ignoreCase = true) && file.length() > 0L
        } ?: return null
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.name.equals(spec.fileName, ignoreCase = true) }?.absolutePath
            ?: candidates.maxByOrNull { it.length() }?.absolutePath
    }

    data class ModelStatus(
        val name: String,
        val type: String,
        val present: Boolean,
        val loaded: Boolean,
        val sizeMb: Long,
        val version: String = "",
        val expectedSha256: String = "",
        val healthy: Boolean = false,
        val verified: Boolean = false
    )

    data class HealthResult(
        val modelId: String,
        val healthy: Boolean,
        val verified: Boolean = false,
        val missing: List<String>,
        val sizeMismatch: List<String>,
        val checksumMismatch: List<String>,
        val unverified: List<String> = emptyList(),
        val message: String
    )

    companion object {
        private val FALLBACK_MODEL_FOLDERS: List<Pair<String, String>> = listOf(
            "brain/gemma-4-e2b" to "llm",
            "speech/shared/sherpa-asr-en" to "asr",
            "speech/shared/sherpa-asr-whisper" to "asr",
            "speech/shared/vad" to "vad",
            "speech/shared/punctuation" to "punctuation",
            "speech/languages/en-IN/tts" to "tts",
            "speech/languages/hi-IN/tts" to "tts",
            "speech/languages/bn-IN/tts" to "tts",
            "speech/languages/ta-IN/tts" to "tts",
            "speech/languages/te-IN/tts" to "tts",
            "speech/languages/kn-IN/tts" to "tts",
            "speech/languages/ml-IN/tts" to "tts",
            "ocr/optional" to "ocr"
        )

        private val RUNTIME_DIRECTORIES: List<String> = listOf(
            "vision/blind-aid",
            "staging"
        )
    }
}
