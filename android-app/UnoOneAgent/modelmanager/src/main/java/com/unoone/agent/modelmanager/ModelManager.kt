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
 * UnoOne V2 stores models below the app-private models root using typed subdirectories. The bundled
 * manifest is the only model catalogue. If it cannot be parsed, model detection and installation fail
 * closed instead of inventing fallback descriptors or directories.
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

    /** Verifies every declared file against its size and SHA-256 when available. */
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
            if (file.sizeBytes == 0L || file.sha256.isBlank()) {
                unverified += file.name
                continue
            }
            if (target.length() != file.sizeBytes) sizeMismatch += file.name
            if (computeSha256(target.absolutePath) != file.sha256.lowercase()) checksumMismatch += file.name
        }

        val healthy = missing.isEmpty() && sizeMismatch.isEmpty() && checksumMismatch.isEmpty()
        val verified = healthy && unverified.isEmpty()
        val message = when {
            !healthy -> "Needs repair (missing/size/hash mismatch)"
            unverified.isNotEmpty() -> "Present — integrity metadata incomplete; release blocked"
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
        manifest.models.map { descriptor ->
            val folder = File(base, descriptor.folder)
            val hasRealFile = folder.walkTopDown().any { file ->
                file.isFile && !file.name.endsWith(".part") && file.length() > 0L
            }
            val present = folder.exists() && folder.isDirectory && hasRealFile
            val sizeMb = if (present) {
                folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            } else 0L
            val health = if (present) modelHealth(descriptor.id) else null
            ModelStatus(
                name = descriptor.folder,
                type = descriptor.type.name,
                present = present,
                loaded = false,
                sizeMb = sizeMb,
                version = descriptor.version,
                expectedSha256 = descriptor.files.firstOrNull()?.sha256.orEmpty(),
                healthy = health?.healthy ?: false,
                verified = health?.verified ?: false
            )
        }.also { models ->
            Logger.d("Detected ${models.count { it.present }} models present out of ${models.size}")
        }
    }

    suspend fun verifyChecksum(path: String, expected: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        expected.matches(Regex("^[a-fA-F0-9]{64}$")) &&
            file.exists() &&
            computeSha256(path) == expected.lowercase()
    }

    private fun computeSha256(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
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
            }
        } catch (e: Exception) {
            Logger.e("Checksum computation failed for $path", e)
            null
        }
    }

    fun getStorageUsageMb(): Long {
        val base = File(appPrivateModelPath)
        if (!base.exists()) return 0L
        return base.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
    }

    fun getModelFolderPath(modelName: String): String =
        File(appPrivateModelPath, modelName).absolutePath

    /** Creates only declared model folders plus non-model runtime directories. */
    fun ensureModelDirectories() {
        val base = File(appPrivateModelPath)
        val manifestFolders = loadManifest().models.map { it.folder }
        (manifestFolders + RUNTIME_DIRECTORIES).distinct().forEach { File(base, it).mkdirs() }
    }

    /**
     * Materializes the optional wake/VAD folder from the verified English ASR files when the
     * manifest declares both models as the exact same artifacts. This avoids a redundant network
     * download while still leaving the dedicated model folder independently verifiable.
     */
    suspend fun repairVadFromVerifiedEnglishAsr(): Boolean = withContext(Dispatchers.IO) {
        val source = findModel("sherpa-asr-en") ?: return@withContext false
        val target = findModel("vad") ?: return@withContext false
        val sourceFiles = source.files.filterNot { it.archive }.associateBy { it.name }
        val targetFiles = target.files.filterNot { it.archive }.associateBy { it.name }
        val manifestsIdentical =
            sourceFiles.keys == targetFiles.keys &&
                sourceFiles.all { (name, file) ->
                    val other = targetFiles[name]
                    other != null &&
                        file.sizeBytes == other.sizeBytes &&
                        file.sha256.equals(other.sha256, ignoreCase = true)
                }
        if (!manifestsIdentical) {
            Logger.w("ModelManager: refusing VAD alias repair because manifests differ")
            return@withContext false
        }
        if (modelHealth(target.id).verified) return@withContext true
        if (!modelHealth(source.id).verified) {
            Logger.w("ModelManager: cannot repair VAD; English ASR source is not verified")
            return@withContext false
        }

        val base = File(appPrivateModelPath)
        val sourceFolder = File(base, source.folder)
        val targetFolder = File(base, target.folder)
        if (!targetFolder.exists() && !targetFolder.mkdirs()) return@withContext false
        for ((name, descriptor) in targetFiles) {
            val sourceFile = File(sourceFolder, name)
            val destination = File(targetFolder, name)
            if (destination.exists() &&
                destination.length() == descriptor.sizeBytes &&
                computeSha256(destination.absolutePath) == descriptor.sha256.lowercase()
            ) continue

            val part = File(targetFolder, "$name.part")
            runCatching { part.delete() }
            try {
                sourceFile.copyTo(part, overwrite = true)
                val verified =
                    part.length() == descriptor.sizeBytes &&
                        computeSha256(part.absolutePath) == descriptor.sha256.lowercase()
                if (!verified) {
                    part.delete()
                    return@withContext false
                }
                if (destination.exists() && !destination.delete()) {
                    part.delete()
                    return@withContext false
                }
                if (!part.renameTo(destination)) {
                    part.delete()
                    return@withContext false
                }
            } catch (e: Exception) {
                part.delete()
                Logger.e("ModelManager: VAD alias repair failed for $name", e)
                return@withContext false
            }
        }
        val repaired = modelHealth(target.id).verified
        if (repaired) Logger.i("ModelManager: installed verified VAD wake pack from English ASR")
        repaired
    }

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
        private val RUNTIME_DIRECTORIES: List<String> = listOf(
            "vision/blind-aid",
            "staging"
        )
    }
}
