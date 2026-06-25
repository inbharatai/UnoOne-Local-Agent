package com.unoone.agent.modelmanager

import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ModelMetadataDao
import com.unoone.agent.storage.entity.ModelMetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads and verifies a model described by a [ModelDescriptor], persisting status to
 * [ModelMetadataDao] when present.
 *
 * Real, no-deps implementation (java.net only):
 * - **Resume**: sends an HTTP `Range: bytes=N-` header against the existing `.part` file; appends
 *   on `206 Partial Content`, restarts cleanly on `200 OK` (server ignored the range).
 * - **Atomic commit**: downloads to `name.part` then renames to the final name, so a crash never
 *   leaves a half-written final file masquerading as complete.
 * - **SHA-256 + size verification** (only when the manifest declares them).
 * - **Corrupt recovery**: on size/checksum mismatch, deletes the bad file and retries the download
 *   exactly once.
 * - **Archive extraction**: ZIP entries (e.g. espeak-ng-data) are extracted into the model folder
 *   and the archive deleted.
 * - **Idempotent**: files already present and valid are skipped (supports re-runs / resume across
 *   app restarts).
 */
class ModelInstaller(
    private val modelBasePath: String,
    private val dao: ModelMetadataDao? = null
) {

    sealed class InstallResult {
        data object Success : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    fun interface ProgressListener {
        fun onProgress(
            modelId: String,
            fileIndex: Int,
            totalFiles: Int,
            file: String,
            downloadedBytes: Long,
            totalBytes: Long
        )
    }

    suspend fun install(
        descriptor: ModelDescriptor,
        listener: ProgressListener? = null
    ): InstallResult = withContext(Dispatchers.IO) {
        val folder = File(modelBasePath, descriptor.folder).apply { mkdirs() }
        setStatus(descriptor, STATUS_DOWNLOADING, folder.absolutePath)
        try {
            descriptor.files.forEachIndexed { index, file ->
                if (file.url.isBlank()) {
                    Logger.w("ModelInstaller: ${file.name} has no download URL — skipping (set the url in the manifest to install it)")
                    return@withContext InstallResult.Failure("No download URL for ${file.name}")
                }
                if (!downloadFile(file, folder, descriptor.id, index, descriptor.files.size, listener)) {
                    setStatus(descriptor, STATUS_CORRUPT, folder.absolutePath)
                    return@withContext InstallResult.Failure("Failed to download/verify ${file.name}")
                }
            }
            setStatus(descriptor, STATUS_PRESENT, folder.absolutePath)
            InstallResult.Success
        } catch (e: Exception) {
            Logger.e("ModelInstaller: install failed for ${descriptor.id}", e)
            setStatus(descriptor, STATUS_ERROR, folder.absolutePath)
            InstallResult.Failure(e.message ?: "Install error")
        }
    }

    /** Downloads one file with resume, verification, and a single corrupt-recovery retry. */
    private fun downloadFile(
        file: ModelFile,
        folder: File,
        modelId: String,
        index: Int,
        total: Int,
        listener: ProgressListener?
    ): Boolean {
        val target = File(folder, file.name)

        // Idempotent fast path: already present and valid.
        if (fileAlreadyValid(target, file)) {
            listener?.onProgress(modelId, index, total, file.name, target.length(), file.sizeBytes.coerceAtLeast(target.length()))
            return true
        }

        val ok = attemptDownloadAndVerify(file, target, modelId, index, total, listener)
        if (!ok) {
            // Leave no corrupt artifact behind — detectModels/health must not see a bad file as present.
            runCatching { target.delete() }
            runCatching { File(folder, "${file.name}.part").delete() }
            return false
        }
        if (file.archive) {
            extractZip(target, folder)
            if (!target.delete()) Logger.w("ModelInstaller: could not delete archive ${target.name} after extraction")
        }
        return true
    }

    /** Downloads (with resume) then verifies size/checksum; on mismatch deletes and retries once. */
    private fun attemptDownloadAndVerify(
        file: ModelFile,
        target: File,
        modelId: String,
        index: Int,
        total: Int,
        listener: ProgressListener?
    ): Boolean {
        if (!attemptDownload(file, target, modelId, index, total, listener)) return false

        if (file.sizeBytes > 0 && target.length() != file.sizeBytes) {
            Logger.w("ModelInstaller: size mismatch for ${file.name} (got ${target.length()}, expected ${file.sizeBytes}); retrying once")
            target.delete()
            return attemptDownload(file, target, modelId, index, total, listener) && verifyAfterDownload(target, file)
        }
        if (file.sha256.isNotBlank() && !verifyChecksum(target, file)) {
            Logger.w("ModelInstaller: checksum mismatch for ${file.name}; deleting and retrying once")
            target.delete()
            return attemptDownload(file, target, modelId, index, total, listener) && verifyAfterDownload(target, file)
        }
        return true
    }

    private fun verifyAfterDownload(target: File, file: ModelFile): Boolean {
        if (file.sizeBytes > 0 && target.length() != file.sizeBytes) return false
        if (file.sha256.isNotBlank() && !verifyChecksum(target, file)) return false
        // NOTE: archive extraction is owned by downloadFile() so a retried archive is extracted
        // exactly once, not twice (extracting here AND again in downloadFile corrupts/rewrites).
        return true
    }

    private fun fileAlreadyValid(target: File, file: ModelFile): Boolean {
        if (!target.exists()) return false
        // Don't trust a 0-byte file when no integrity fields are declared — it may be a truncated
        // download; force a re-download instead of short-circuiting to "valid".
        if (file.sizeBytes == 0L && file.sha256.isBlank() && target.length() == 0L) return false
        if (file.sizeBytes > 0 && target.length() != file.sizeBytes) return false
        return file.sha256.isBlank() || verifyChecksum(target, file)
    }

    private fun attemptDownload(
        file: ModelFile,
        target: File,
        modelId: String,
        index: Int,
        total: Int,
        listener: ProgressListener?
    ): Boolean {
        val temp = File(target.parentFile, "${file.name}.part")
        val existingBytes = if (temp.exists()) temp.length() else 0L

        // If a prior run finished downloading but crashed before the .part→final rename, the temp
        // is already complete. When we can verify that (sizeBytes known and reached), skip the
        // network and commit directly — otherwise the server's 416 "Range Not Satisfiable" would
        // make re-install fail forever.
        if (file.sizeBytes > 0 && existingBytes >= file.sizeBytes) {
            Logger.i("ModelInstaller: ${file.name} .part already complete ($existingBytes bytes); committing")
            return commitTemp(temp, target)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(file.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0) {
                // Server says the range is unsatisfiable — we already hold everything it would send.
                // Only safe to commit when we can verify completeness; otherwise bail rather than
                // risk committing a partial file with no integrity fields to verify against.
                if (file.sizeBytes > 0 && existingBytes >= file.sizeBytes) {
                    Logger.i("ModelInstaller: HTTP 416 for ${file.name}; .part complete, committing")
                    return commitTemp(temp, target)
                }
                Logger.w("ModelInstaller: HTTP 416 for ${file.name}; cannot verify completeness, failing")
                return false
            }
            if (code !in 200..299) {
                Logger.w("ModelInstaller: HTTP $code for ${file.name}")
                return false
            }
            val resumeSupported = code == 206
            val append = resumeSupported && existingBytes > 0
            if (!append && existingBytes > 0) temp.delete() // server sent full file; start over

            val reportedTotal = conn.getHeaderField("Content-Range")
                ?.substringAfter('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: (conn.contentLengthLong.takeIf { it > 0 }?.let { it + if (append) existingBytes else 0 })
                ?: file.sizeBytes

            FileOutputStream(temp, append).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    var written = if (append) existingBytes else 0L
                    while (input.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                        written += read
                        listener?.onProgress(
                            modelId, index, total, file.name,
                            written, reportedTotal.takeIf { it > 0 } ?: written
                        )
                    }
                }
            }

            commitTemp(temp, target)
        } catch (e: Exception) {
            Logger.e("ModelInstaller: download error for ${file.name}", e)
            false
        } finally {
            // Always release the connection — previously a mid-stream exception leaked it.
            runCatching { conn?.disconnect() }
        }
    }

    /** Atomically commits the .part temp file to its final name (rename, with a copy fallback). */
    private fun commitTemp(temp: File, target: File): Boolean = try {
        if (target.exists() && !target.delete()) {
            Logger.w("ModelInstaller: could not replace existing ${target.name}; copying over")
            temp.copyTo(target, overwrite = true)
            temp.delete()
        } else if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        true
    } catch (e: Exception) {
        Logger.e("ModelInstaller: commit failed for ${target.name}", e)
        false
    }

    private fun verifyChecksum(file: File, descriptor: ModelFile): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        actual == descriptor.sha256.lowercase()
    } catch (e: Exception) {
        Logger.e("ModelInstaller: checksum failed for ${file.name}", e)
        false
    }

    private fun extractZip(zipFile: File, destFolder: File) {
        destFolder.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destFolder, entry.name)
                // Guard against zip-slip (paths escaping the dest folder).
                val canonicalDest = destFolder.canonicalPath
                val canonicalOut = out.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    Logger.w("ModelInstaller: skipping zip entry outside dest folder: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun setStatus(descriptor: ModelDescriptor, status: String, path: String) {
        val dao = this.dao ?: return
        val existing = dao.getByName(descriptor.id)
        val entity = ModelMetadataEntity(
            id = existing?.id ?: 0,
            modelName = descriptor.id,
            modelType = descriptor.type.name,
            localPath = path,
            checksum = existing?.checksum ?: "",
            status = status,
            lastLoadedAt = existing?.lastLoadedAt
        )
        if (existing == null) dao.insert(entity) else dao.update(entity)
    }

    companion object {
        const val STATUS_MISSING = "missing"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_PRESENT = "present"
        const val STATUS_LOADED = "loaded"
        const val STATUS_CORRUPT = "corrupt"
        const val STATUS_ERROR = "error"

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
    }
}