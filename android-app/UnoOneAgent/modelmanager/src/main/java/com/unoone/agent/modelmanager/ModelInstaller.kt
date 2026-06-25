package com.unoone.agent.modelmanager

import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ModelMetadataDao
import com.unoone.agent.storage.entity.ModelMetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

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
    private val dao: ModelMetadataDao? = null,
    /**
     * Resolves a bundled asset name to an [InputStream], or null if not present. Wired by
     * [ModelManager] to `context.assets.open(name)`; null in unit tests (asset-backed files then
     * fail with a clear error rather than crashing). Lets a manifest file ship from the APK's
     * `assets/` instead of an HTTP download (see [ModelFile.asset]).
     */
    private val assetReader: ((String) -> InputStream?)? = null
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
                val ok = if (!file.asset.isNullOrBlank()) {
                    installFromAsset(file, folder, descriptor.id, index, descriptor.files.size, listener)
                } else {
                    if (file.url.isBlank()) {
                        Logger.w("ModelInstaller: ${file.name} has no download URL — skipping (set the url in the manifest to install it)")
                        return@withContext InstallResult.Failure("No download URL for ${file.name}")
                    }
                    downloadFile(file, folder, descriptor.id, index, descriptor.files.size, listener)
                }
                if (!ok) {
                    setStatus(descriptor, STATUS_CORRUPT, folder.absolutePath)
                    return@withContext InstallResult.Failure("Failed to install ${file.name}")
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

        // Archive fast path: the zip is deleted after extraction, so "valid" means the extracted
        // directory already exists and is non-empty (not that the zip is present).
        if (file.archive && archiveAlreadyExtracted(file, folder)) {
            listener?.onProgress(modelId, index, total, file.name, file.sizeBytes, file.sizeBytes)
            return true
        }

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
            extractArchive(target, folder)
            if (!target.delete()) Logger.w("ModelInstaller: could not delete archive ${target.name} after extraction")
        }
        return true
    }

    /**
     * Installs a file from a bundled app asset ([ModelFile.asset]) instead of an HTTP download.
     * Copies the asset to `name`, verifies declared size/sha, and — for archives — extracts into
     * the model folder then deletes the zip. Idempotent: skips when the (non-archive) file is
     * already valid or the (archive) extraction already exists.
     */
    private fun installFromAsset(
        file: ModelFile,
        folder: File,
        modelId: String,
        index: Int,
        total: Int,
        listener: ProgressListener?
    ): Boolean {
        val reader = assetReader ?: run {
            Logger.e("ModelInstaller: ${file.name} is asset-backed ('${file.asset}') but no asset reader is configured")
            return false
        }
        val assetName = file.asset ?: return false

        // Archive fast path: already extracted → skip.
        if (file.archive && archiveAlreadyExtracted(file, folder)) {
            listener?.onProgress(modelId, index, total, file.name, file.sizeBytes, file.sizeBytes)
            return true
        }

        val target = File(folder, file.name)
        // Non-archive fast path: already present and valid.
        if (!file.archive && fileAlreadyValid(target, file)) {
            listener?.onProgress(modelId, index, total, file.name, target.length(), file.sizeBytes.coerceAtLeast(target.length()))
            return true
        }

        target.parentFile?.mkdirs()
        val input = try {
            reader(assetName)
        } catch (e: Exception) {
            Logger.e("ModelInstaller: asset reader threw for '$assetName'", e)
            null
        }
        if (input == null) {
            Logger.e("ModelInstaller: asset '$assetName' not found for ${file.name}")
            return false
        }
        return try {
            input.use { src ->
                FileOutputStream(target).use { out -> src.copyTo(out) }
            }
            if (file.sizeBytes > 0 && target.length() != file.sizeBytes) {
                Logger.w("ModelInstaller: asset ${file.name} size mismatch (got ${target.length()}, expected ${file.sizeBytes})")
                return false
            }
            if (file.sha256.isNotBlank() && !verifyChecksum(target, file)) {
                Logger.w("ModelInstaller: asset ${file.name} checksum mismatch")
                return false
            }
            if (file.archive) {
                extractArchive(target, folder)
                if (!target.delete()) Logger.w("ModelInstaller: could not delete asset archive ${target.name} after extraction")
            }
            listener?.onProgress(modelId, index, total, file.name, file.sizeBytes, file.sizeBytes)
            true
        } catch (e: Exception) {
            Logger.e("ModelInstaller: asset copy failed for ${file.name}", e)
            runCatching { target.delete() }
            false
        }
    }

    /**
     * The directory an archive extracts to. When [ModelFile.extractsTo] is set, that exact top
     * directory is used (required for tarballs whose top dir differs from the archive name, e.g.
     * `sherpa-onnx-whisper-tiny.tar.bz2` → `sherpa-onnx-whisper-tiny/`, and for `.tar.bz2` whose
     * double extension breaks strip-last-extension). Otherwise fall back to stripping the last
     * extension of `name` (`<dir>.zip` → `<dir>/`), preserving the original espeak-ng-data convention.
     */
    private fun archiveOutputDir(file: ModelFile, folder: File): File =
        if (!file.extractsTo.isNullOrBlank()) File(folder, file.extractsTo)
        else File(folder, file.name.substringBeforeLast('.'))

    /** True when an archive has already been extracted (output dir present and non-empty). */
    private fun archiveAlreadyExtracted(file: ModelFile, folder: File): Boolean {
        val dir = archiveOutputDir(file, folder)
        return dir.isDirectory && !dir.listFiles().isNullOrEmpty()
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
        // Safety: a file.name with a subpath (e.g. nested under a dir) needs its parent created.
        target.parentFile?.mkdirs()
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

    /**
     * Extracts an archive into [destFolder]. Supports ZIP (via `java.util.zip`, the original path —
     * unchanged) and tar.bz2 / tar.gz / tar (via Apache commons-compress) so a manifest entry can
     * point at a public model tarball (e.g. `sherpa-onnx-whisper-tiny.tar.bz2`) without re-hosting
     * its contents. The archive is deleted by the caller after extraction. All entry paths are
     * guarded against zip-slip / tar-slip (paths escaping the dest folder).
     */
    private fun extractArchive(archiveFile: File, destFolder: File) {
        destFolder.mkdirs()
        val name = archiveFile.name.lowercase()
        val isZip = name.endsWith(".zip")
        if (isZip) {
            // Original ZIP path, verbatim — preserves the behavior the existing asset tests rely on.
            ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destFolder, entry.name)
                    if (!isInsideDest(out, destFolder)) {
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
            return
        }

        // tar / tar.bz2 / tar.gz — decompress layer first, then the tar archive layer.
        val raw = archiveFile.inputStream().buffered()
        val decompressed: java.io.InputStream = when {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") ->
                BZip2CompressorInputStream(raw)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                GzipCompressorInputStream(raw)
            else -> raw // plain .tar
        }
        val tarIn = TarArchiveInputStream(decompressed)
        tarIn.use { ais ->
            var entry = ais.nextEntry
            while (entry != null) {
                val out = File(destFolder, entry.name)
                if (!isInsideDest(out, destFolder)) {
                    Logger.w("ModelInstaller: skipping tar entry outside dest folder: ${entry.name}")
                    entry = ais.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        // Copy only this entry's bytes; TarArchiveInputStream.read() returns -1 at
                        // the entry boundary, so copyTo stops there (not at end of the whole tar).
                        ais.copyTo(fos)
                    }
                }
                entry = ais.nextEntry
            }
        }
    }

    /** True when [out] resolves inside [destFolder] (zip-slip / tar-slip guard). */
    private fun isInsideDest(out: File, destFolder: File): Boolean {
        val canonicalDest = destFolder.canonicalPath
        val canonicalOut = out.canonicalPath
        return canonicalOut == canonicalDest ||
            canonicalOut.startsWith(canonicalDest + File.separator)
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