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
import kotlinx.serialization.json.Json

/**
 * Source-of-truth model filesystem facade.
 *
 * UnoOne stores models below the app-private models root using typed subdirectories. The bundled
 * manifest is the only install catalogue. If it cannot be parsed, model detection and installation
 * fail closed instead of inventing fallback descriptors or directories.
 */
class ModelManager(
    private val context: Context,
    private val modelMetadataDao: ModelMetadataDao? = null
) {

    private val manifestLoader = ModelManifestLoader()
    private val artifactVerifier = ArtifactVerifier(PreferencesVerificationRecordStore(context))
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

    /** Verifies every declared file against its exact size and SHA-256. */
    suspend fun modelHealth(id: String, forceVerify: Boolean = false): HealthResult = withContext(Dispatchers.IO) {
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
                } else if (!installer.archiveAlreadyExtracted(file, folder)) {
                    checksumMismatch += file.name
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
            if (target.length() != file.sizeBytes) {
                sizeMismatch += file.name
                continue
            }
            val verification = artifactVerifier.verify(
                descriptor,
                file,
                target,
                loadManifest().manifestVersion,
                force = forceVerify
            )
            if (!verification.verified) checksumMismatch += file.name
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
        shouldCancel: () -> Boolean = { false },
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
        val result = installer.install(
            descriptor,
            onProgress?.let { callback ->
                ModelInstaller.ProgressListener { mid, fileIndex, totalFiles, file, downloaded, total ->
                    callback(mid, fileIndex, totalFiles, file, downloaded, total)
                }
            },
            shouldCancel = shouldCancel
        )
        if (result is ModelInstaller.InstallResult.Success) {
            // Installer already performed the mandatory full hash before activation. Seed the
            // metadata-bound cache immediately so startup/status/browser acquisition do not hash
            // the same multi-gigabyte file again.
            val manifestVersion = loadManifest().manifestVersion
            val folder = File(appPrivateModelPath, descriptor.folder)
            descriptor.files.filterNot { it.archive }.forEach { file ->
                artifactVerifier.recordVerified(
                    descriptor = descriptor,
                    fileDescriptor = file,
                    file = File(folder, file.name),
                    manifestVersion = manifestVersion
                )
            }
        }
        return result
    }

    /** Deletes only a manifest-resolved folder beneath the app-private models root. */
    suspend fun uninstallModel(id: String) = withContext(Dispatchers.IO) {
        val descriptor = findModel(id)
            ?: run {
                Logger.w("ModelManager: refusing uninstall for unknown model '$id'")
                return@withContext
            }
        val base = File(appPrivateModelPath)
        val folder = File(base, descriptor.folder)
        if (!isSafeChild(base, folder)) {
            Logger.w("ModelManager: refusing to uninstall '$id' outside models root (${folder.canonicalPath})")
            return@withContext
        }
        deleteDirectoryContents(folder)
        artifactVerifier.invalidate(id)
        modelMetadataDao?.deleteByName(id)
        Logger.i("ModelManager: uninstalled $id")
    }

    /**
     * Removes the obsolete E2B artifact only after the E4B catalogue entry is fully verified.
     *
     * Existing installations may still have `brain/gemma-4-e2b` even though it no longer appears in
     * the active manifest. Generic uninstall cannot safely resolve an unknown legacy id, so this
     * migration uses one hard-coded historical relative path guarded by canonical-path checks.
     */
    @Deprecated("Integrity alone is not a deletion qualification")
    suspend fun removeLegacyE2BIfE4BVerified(): LegacyCleanupResult = withContext(Dispatchers.IO) {
        LegacyCleanupResult(
            removed = false,
            legacyPresent = legacyE2BPresent(),
            message = "Legacy cleanup is qualification-gated; checksum verification alone cannot remove E2B"
        )
    }

    suspend fun removeLegacyE2BIfQualified(userApproved: Boolean): LegacyCleanupResult = withContext(Dispatchers.IO) {
        val rejection = E4bCleanupGate.rejectionReason(readQualificationRecord(), userApproved)
        if (rejection != null) {
            return@withContext LegacyCleanupResult(
                removed = false,
                legacyPresent = legacyE2BFolder().exists(),
                message = "$rejection; legacy brain was preserved"
            )
        }

        // Qualification is not yet backed by release-device evidence; preserve the legacy fallback.
        LegacyCleanupResult(false, legacyE2BPresent(), "Legacy deletion disabled pending device qualification")
    }

    fun saveQualificationRecord(record: E4bQualificationRecord) {
        val json = Json.encodeToString(E4bQualificationRecord.serializer(), record)
        context.getSharedPreferences(QUALIFICATION_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_E4B_QUALIFICATION, json).apply()
    }

    fun readQualificationRecord(): E4bQualificationRecord? {
        val raw = context.getSharedPreferences(QUALIFICATION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_E4B_QUALIFICATION, null) ?: return null
        return runCatching { Json.decodeFromString(E4bQualificationRecord.serializer(), raw) }
            .getOrNull()
    }

    fun legacyE2BPresent(): Boolean = legacyE2BFolder().let { folder ->
        folder.isDirectory && folder.walkTopDown().any { it.isFile && it.length() > 0L }
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
    suspend fun repairKwsFromVerifiedEnglishAsr(): Boolean = withContext(Dispatchers.IO) {
        val source = findModel("sherpa-asr-en") ?: return@withContext false
        val target = findModel("sherpa-kws-en") ?: return@withContext false
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
            Logger.w("ModelManager: refusing KWS alias repair because manifests differ")
            return@withContext false
        }
        if (modelHealth(target.id).verified) return@withContext true
        if (!modelHealth(source.id).verified) {
            Logger.w("ModelManager: cannot repair KWS; English ASR source is not verified")
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
                Logger.e("ModelManager: KWS alias repair failed for $name", e)
                return@withContext false
            }
        }
        val repaired = modelHealth(target.id).verified
        if (repaired) Logger.i("ModelManager: installed verified KWS wake pack from English ASR")
        repaired
    }

    suspend fun getLlmModelPath(): String? = getLlmModelPath(BrainModelRegistry.defaultProfile)

    /**
     * Returns only the exact, manifest-declared, integrity-verified model file.
     *
     * There is deliberately no "largest .litertlm" fallback: a stale E2B file, web artifact,
     * incomplete copy or manually dropped model must never be selected as UnoOne's brain.
     */
    suspend fun getLlmModelPath(spec: BrainModelSpec, forceVerify: Boolean = false): String? {
        if (spec.runtime != com.unoone.agent.core.model.BrainRuntime.LITERT_LM) return null
        val descriptor = findModel(spec.manifestId) ?: return null
        val artifact = descriptor.files.singleOrNull { file ->
            !file.archive && file.name.equals(spec.fileName, ignoreCase = false)
        } ?: return null
        if (descriptor.folder != spec.modelFolder || artifact.sha256.isBlank() || artifact.sizeBytes <= 0L) {
            return null
        }

        val folder = File(appPrivateModelPath, spec.modelFolder)
        val exact = File(folder, spec.fileName)
        if (!exact.isFile || exact.name.endsWith(".part") || exact.length() != artifact.sizeBytes) return null
        val verified = artifactVerifier.verify(
            descriptor,
            artifact,
            exact,
            loadManifest().manifestVersion,
            force = forceVerify
        )
        if (!verified.verified) return null
        return exact.absolutePath
    }

    /** MNN consumes a complete verified folder, never a single LiteRT model path. */
    suspend fun getMnnModelFolder(spec: BrainModelSpec): String? {
        if (spec.runtime != com.unoone.agent.core.model.BrainRuntime.MNN) return null
        val descriptor = findModel(spec.manifestId) ?: return null
        if (descriptor.folder != spec.modelFolder || !modelHealth(spec.manifestId).verified) return null
        return File(appPrivateModelPath, descriptor.folder).absolutePath
    }

    /** User/developer explicit verification always performs a complete SHA-256 pass. */
    suspend fun verifyLlmArtifact(spec: BrainModelSpec = BrainModelRegistry.defaultProfile): HealthResult {
        return modelHealth(spec.manifestId, forceVerify = true)
    }

    private fun legacyE2BFolder(): File = File(appPrivateModelPath, LEGACY_E2B_RELATIVE_FOLDER)

    private fun isSafeChild(base: File, candidate: File): Boolean {
        val baseCanonical = base.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        return candidateCanonical != baseCanonical &&
            candidateCanonical.path.startsWith(baseCanonical.path + File.separator)
    }

    private fun deleteDirectoryContents(folder: File) {
        if (!folder.exists()) return
        folder.walkTopDown().sortedByDescending { it.path }.forEach { file ->
            runCatching { file.delete() }
        }
        runCatching { folder.delete() }
    }

    private fun deleteDirectoryContentsReportingFailures(folder: File): List<String> {
        if (!folder.exists()) return emptyList()
        val failures = mutableListOf<String>()
        folder.walkTopDown().sortedByDescending { it.path }.forEach { file ->
            if (file.exists() && !file.delete()) failures += file.name
        }
        return failures
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

    data class LegacyCleanupResult(
        val removed: Boolean,
        val legacyPresent: Boolean,
        val message: String
    )

    companion object {
        private const val LEGACY_E2B_ID = "gemma-4-e2b"
        private const val LEGACY_E2B_RELATIVE_FOLDER = "brain/gemma-4-e2b"
        private const val QUALIFICATION_PREFS = "e4b_device_qualification_v1"
        private const val KEY_E4B_QUALIFICATION = "qualified_record"
        private val RUNTIME_DIRECTORIES: List<String> = listOf(
            "vision/blind-aid",
            "staging"
        )
    }
}
