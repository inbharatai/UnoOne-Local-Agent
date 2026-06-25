package com.unoone.agent.modelmanager

import android.content.Context
import android.os.Environment
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ModelMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ModelManager(
    private val context: Context,
    private val modelMetadataDao: ModelMetadataDao? = null
) {

    private val manifestLoader = ModelManifestLoader()
    private val installer: ModelInstaller by lazy {
        ModelInstaller(appPrivateModelPath, modelMetadataDao) { name ->
            // Resolves a bundled asset; null (not an exception) when absent so the installer can
            // emit a clear "asset not found" failure instead of crashing on install.
            runCatching { context.assets.open(name) }.getOrNull()
        }
    }

    private val modelBasePath: String
        get() = Environment.getExternalStorageDirectory()
            .resolve("Android/data/${context.packageName}/files/models")
            .absolutePath

    private val appPrivateModelPath: String
        get() = context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.resolve("models").absolutePath

    /** Loads (and caches) the bundled model manifest. */
    fun loadManifest(): ModelManifest = manifestLoader.load(context)

    /** Finds a manifest descriptor by model id, or null. */
    fun findModel(id: String): ModelDescriptor? = manifestLoader.find(context, id)

    /**
     * Verifies on-disk health of a model: every declared file must exist, match its declared size
     * (if any), and match its declared SHA-256 (if any). Missing/mismatched files are reported so the
     * UI can offer a re-install.
     */
    suspend fun modelHealth(id: String): HealthResult = withContext(Dispatchers.IO) {
        val descriptor = findModel(id)
            ?: return@withContext HealthResult(id, healthy = false, missing = emptyList(), sizeMismatch = emptyList(), checksumMismatch = emptyList(), "Unknown model id")
        val folder = File(appPrivateModelPath, descriptor.folder)
        val missing = mutableListOf<String>()
        val sizeMismatch = mutableListOf<String>()
        val checksumMismatch = mutableListOf<String>()
        for (file in descriptor.files) {
            // Archives are deleted after extraction, so health verifies the extracted directory
            // rather than the (deleted) archive. The directory is ModelFile.extractsTo when set
            // (required for tarballs whose top dir differs from the archive name, e.g. the
            // whisper-tiny tar), else the strip-last-extension of the archive name (espeak convention).
            if (file.archive) {
                val extractedName = file.extractsTo?.takeIf { it.isNotBlank() }
                    ?: file.name.substringBeforeLast('.')
                val extracted = File(folder, extractedName)
                if (!extracted.exists() || !extracted.isDirectory || extracted.listFiles().isNullOrEmpty()) {
                    missing.add(file.name)
                }
                continue
            }
            val target = File(folder, file.name)
            if (!target.exists()) {
                missing.add(file.name)
                continue
            }
            // When the manifest declares no integrity fields for a file we cannot verify bytes —
            // require the file to at least be non-empty so a 0-byte / truncated artifact is not
            // reported as healthy (and so the installer re-downloads it instead of trusting it).
            if (file.sizeBytes == 0L && file.sha256.isBlank() && target.length() == 0L) {
                missing.add(file.name)
                continue
            }
            if (file.sizeBytes > 0 && target.length() != file.sizeBytes) sizeMismatch.add(file.name)
            if (file.sha256.isNotBlank() && computeSha256(target.absolutePath) != file.sha256.lowercase()) checksumMismatch.add(file.name)
        }
        val healthy = missing.isEmpty() && sizeMismatch.isEmpty() && checksumMismatch.isEmpty()
        HealthResult(id, healthy, missing, sizeMismatch, checksumMismatch, if (healthy) "OK" else "Needs repair")
    }

    /** Downloads and verifies a model, streaming progress to [onProgress]. */
    suspend fun installModel(
        id: String,
        onProgress: ((modelId: String, fileIndex: Int, totalFiles: Int, file: String, downloaded: Long, total: Long) -> Unit)? = null
    ): ModelInstaller.InstallResult {
        val descriptor = findModel(id)
            ?: return ModelInstaller.InstallResult.Failure("Unknown model id: $id")
        return installer.install(descriptor, onProgress?.let { ModelInstaller.ProgressListener { mid, fi, tf, f, d, t -> it(mid, fi, tf, f, d, t) } })
    }

    /** Deletes a model folder and clears its persisted metadata. */
    suspend fun uninstallModel(id: String) = withContext(Dispatchers.IO) {
        val descriptor = findModel(id)
        val base = File(appPrivateModelPath)
        val folder = if (descriptor != null) File(base, descriptor.folder) else File(base, id)
        // Guard against path traversal: the resolved folder must stay inside the models root.
        // A malformed id ("../..") or a tampered manifest folder must never delete outside models/.
        val baseCanonical = base.canonicalPath
        val folderCanonical = folder.canonicalPath
        if (folderCanonical != baseCanonical && !folderCanonical.startsWith(baseCanonical + File.separator)) {
            Logger.w("ModelManager: refusing to uninstall '$id' — resolves outside models dir ($folderCanonical)")
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
        val models = mutableListOf<ModelStatus>()
        val base = File(appPrivateModelPath)
        if (!base.exists()) base.mkdirs()

        val manifest = loadManifest()
        // Known model folders: manifest first (source of truth), then any legacy hardcoded ones.
        val known: List<Pair<String, String>> = manifest.models.map { it.folder to it.type.name }
            .ifEmpty {
                // Legacy fallback when the manifest asset fails to load. Mirrors the manifest's
                // per-language folder layout (English + shared whisper ASR + per-language MMS TTS).
                listOf(
                    "gemma-local" to "llm",
                    "sherpa-asr-en" to "asr",
                    "sherpa-asr-whisper" to "asr",
                    "sherpa-tts-en" to "tts",
                    "sherpa-tts-hin" to "tts",
                    "sherpa-tts-ben" to "tts",
                    "sherpa-tts-tam" to "tts",
                    "sherpa-tts-tel" to "tts",
                    "sherpa-tts-kan" to "tts",
                    "sherpa-tts-mal" to "tts",
                    "vad" to "vad",
                    "punctuation" to "punctuation",
                    "ocr-optional" to "ocr"
                )
            }

        for ((folderName, type) in known) {
            val folder = File(base, folderName)
            // A folder holding only a stale crashed `.part` is not an installed model — require at
            // least one real (non-.part) file before reporting present, so the count isn't inflated.
            val realFiles = folder.listFiles { f -> f.isFile && !f.name.endsWith(".part") }
            val present = folder.exists() && folder.isDirectory && !realFiles.isNullOrEmpty()
            val sizeMb = if (present) folder.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024 * 1024) else 0L
            val descriptor = manifest.findByFolder(folderName)
            val health = if (descriptor != null && present) modelHealth(descriptor.id).healthy else false
            models.add(
                ModelStatus(
                    name = folderName,
                    type = type,
                    present = present,
                    loaded = false,
                    sizeMb = sizeMb,
                    version = descriptor?.version ?: "",
                    expectedSha256 = descriptor?.files?.firstOrNull()?.sha256 ?: "",
                    healthy = health
                )
            )
        }

        Logger.d("Detected ${models.count { it.present }} models present out of ${models.size}")
        models
    }

    suspend fun verifyChecksum(path: String, expected: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(path)
            if (!file.exists()) return@withContext false
            computeSha256(path) == expected.lowercase()
        } catch (e: Exception) {
            Logger.e("Checksum verification failed for $path", e)
            false
        }
    }

    /** Blocking SHA-256 of a file (lowercase hex), or null on error. Used by both [verifyChecksum]
     *  (suspend) and [modelHealth] (non-suspend, runs on the caller's thread — typically IO via the UI VM). */
    private fun computeSha256(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Logger.e("Checksum computation failed for $path", e)
            null
        }
    }

    fun getStorageUsageMb(): Long {
        val base = File(appPrivateModelPath)
        if (!base.exists()) return 0L
        return base.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024 * 1024)
    }

    fun getModelFolderPath(modelName: String): String {
        return File(appPrivateModelPath, modelName).absolutePath
    }

    fun ensureModelDirectories() {
        val base = File(appPrivateModelPath)
        val manifest = loadManifest()
        val folders = manifest.models.map { it.folder }
            .ifEmpty {
                listOf(
                    "gemma-local", "sherpa-asr-en", "sherpa-asr-whisper",
                    "sherpa-tts-en", "sherpa-tts-hin", "sherpa-tts-ben", "sherpa-tts-tam",
                    "sherpa-tts-tel", "sherpa-tts-kan", "sherpa-tts-mal",
                    "vad", "punctuation", "ocr-optional"
                )
            }
        folders.forEach { File(base, it).mkdirs() }
    }

    /**
     * Returns the absolute path of the first Gemma 3n E4B `.litertlm` model found,
     * or null if none is present.
     */
    fun getLlmModelPath(): String? {
        val gemmaFolder = File(appPrivateModelPath, "gemma-local")
        return gemmaFolder.listFiles { file ->
            file.isFile && file.name.endsWith(".litertlm", ignoreCase = true)
        }?.firstOrNull()?.absolutePath
    }

    data class ModelStatus(
        val name: String,
        val type: String,
        val present: Boolean,
        val loaded: Boolean,
        val sizeMb: Long,
        val version: String = "",
        val expectedSha256: String = "",
        val healthy: Boolean = false
    )

    /** Result of verifying a model's on-disk files against its manifest. */
    data class HealthResult(
        val modelId: String,
        val healthy: Boolean,
        val missing: List<String>,
        val sizeMismatch: List<String>,
        val checksumMismatch: List<String>,
        val message: String
    )
}