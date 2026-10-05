package com.unoone.agent.modelmanager

import android.os.StatFs
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
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
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
    @Volatile private var lastFailureReason: String? = null
    @Volatile private var lastFailureRetryable: Boolean = false

    sealed class InstallResult {
        data object Success : InstallResult()
        data class Failure(val reason: String, val retryable: Boolean = false) : InstallResult()
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
        listener: ProgressListener? = null,
        shouldCancel: () -> Boolean = { false }
    ): InstallResult = withContext(Dispatchers.IO) {
        val folder = File(modelBasePath, descriptor.folder).apply { mkdirs() }
        setStatus(descriptor, STATUS_DOWNLOADING, folder.absolutePath)
        try {
            descriptor.files.forEachIndexed { index, file ->
                if (shouldCancel()) {
                    return@withContext InstallResult.Failure("Download cancelled; partial data preserved for resume")
                }
                lastFailureReason = null
                lastFailureRetryable = false
                val ok = if (!file.asset.isNullOrBlank()) {
                    installFromAsset(file, folder, descriptor.id, index, descriptor.files.size, listener)
                } else {
                    if (file.url.isBlank()) {
                        Logger.w("ModelInstaller: ${file.name} has no download URL — skipping (set the url in the manifest to install it)")
                        return@withContext InstallResult.Failure("No download URL for ${file.name}")
                    }
                    downloadFile(file, folder, descriptor.id, index, descriptor.files.size, listener, shouldCancel)
                }
                if (!ok) {
                    setStatus(descriptor, STATUS_CORRUPT, folder.absolutePath)
                    return@withContext InstallResult.Failure(
                        lastFailureReason ?: "Failed to install ${file.name}",
                        retryable = lastFailureRetryable
                    )
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
        listener: ProgressListener?,
        shouldCancel: () -> Boolean
    ): Boolean {
        val target = File(folder, file.name)

        // Archive fast path: the zip is deleted after extraction, so "valid" means the extracted
        // payload exactly matches its archive-bound inventory (not just directory presence).
        if (file.archive && archiveAlreadyExtracted(file, folder)) {
            listener?.onProgress(modelId, index, total, file.name, file.sizeBytes, file.sizeBytes)
            return true
        }

        // Idempotent fast path: already present and valid.
        if (!file.archive && fileAlreadyValid(target, file)) {
            listener?.onProgress(modelId, index, total, file.name, target.length(), file.sizeBytes.coerceAtLeast(target.length()))
            return true
        }

        val storageError = storagePreflight(file, target)
        if (storageError != null) {
            lastFailureReason = storageError
            Logger.w("ModelInstaller: $storageError")
            return false
        }
        val ok = attemptDownloadAndVerify(file, target, modelId, index, total, listener, shouldCancel)
        if (!ok) {
            // Leave no corrupt artifact behind — detectModels/health must not see a bad file as present.
            // Keep the previous target until verified atomic activation succeeds.
            // Preserve an ordinary interrupted .part for resume. attemptDownload deletes it only
            // when metadata proves it impossible/corrupt (for example larger than expected).
            return false
        }
        if (file.archive) {
            extractArchiveVerified(target, folder, file)
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
        val part = File(target.parentFile, "${target.name}.part")
        return try {
            input.use { src ->
                FileOutputStream(part).use { out -> src.copyTo(out); out.fd.sync() }
            }
            if (!verifyAfterDownload(part, file)) { part.delete(); return false }
            if (!commitTemp(part, target)) return false
            if (file.archive) {
                extractArchiveVerified(target, folder, file)
                if (!target.delete()) Logger.w("ModelInstaller: could not delete asset archive ${target.name} after extraction")
            }
            listener?.onProgress(modelId, index, total, file.name, file.sizeBytes, file.sizeBytes)
            true
        } catch (e: Exception) {
            Logger.e("ModelInstaller: asset copy failed for ${file.name}", e)
            runCatching { part.delete() }
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

    private fun archiveIdentity(file: ModelFile) = "${file.name}:${file.sizeBytes}:${file.sha256.lowercase()}"
    private val inventoryName = ".unoone-inventory-v2"

    /** A local receipt bound to the pinned archive, plus a full rehash of every regular output.
     * Legacy identity-only markers are deliberately not accepted. This is not a signed receipt:
     * an attacker able to rewrite both app-private data and receipt is outside this trust boundary.
     */
    internal fun archiveAlreadyExtracted(file: ModelFile, folder: File): Boolean = runCatching {
        require(file.sizeBytes > 0 && file.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        val output = archiveOutputDir(file, folder)
        require(output.canonicalFile.parentFile == folder.canonicalFile)
        val receipt = File(output, inventoryName)
        require(!Files.isSymbolicLink(receipt.toPath()))
        receipt.readText() == archiveIdentity(file) + "\n" + inventory(output)
    }.getOrDefault(false)

    private fun inventory(root: File): String {
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        val rows = mutableListOf<String>()
        fun visit(dir: File) {
            val children = checkNotNull(dir.listFiles()) { "Unreadable archive directory" }
            for (child in children) {
                require(!Files.isSymbolicLink(child.toPath())) { "Link in archive output" }
                if (child == File(root, inventoryName)) continue
                if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) visit(child)
                else {
                    require(Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS))
                    val digest = MessageDigest.getInstance("SHA-256")
                    child.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    val path = child.relativeTo(root).invariantSeparatorsPath
                    require(!path.contains('\n') && !path.contains('\t'))
                    rows += "$path\t${child.length()}\t${digest.digest().joinToString("") { "%02x".format(it) }}"
                }
            }
        }
        visit(root)
        require(rows.isNotEmpty()) { "Archive contains no regular output files" }
        return rows.sorted().joinToString("\n")
    }

    private fun extractArchiveVerified(target: File, folder: File, file: ModelFile) {
        require(file.sizeBytes > 0 && file.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Archive requires pinned size and SHA256" }
        check(verifyAfterDownload(target, file)) { "Archive integrity mismatch" }
        val live = archiveOutputDir(file, folder)
        require(live.canonicalFile.parentFile == folder.canonicalFile && !Files.isSymbolicLink(live.toPath()))
        val stage = File(folder, ".extract-${UUID.randomUUID()}")
        check(stage.mkdir())
        val backup = File(folder, ".backup-${UUID.randomUUID()}")
        try {
            extractArchive(target, stage)
            val output = File(stage, live.name)
            require(stage.listFiles()?.toList() == listOf(output)) { "Unexpected archive root outputs" }
            val receipt = File(output, inventoryName)
            require(!receipt.exists()) { "Reserved inventory entry" }
            val contents = archiveIdentity(file) + "\n" + inventory(output)
            FileOutputStream(receipt).use { it.write(contents.toByteArray()); it.fd.sync() }
            check(archiveAlreadyExtracted(file, stage)) { "Staged inventory verification failed" }
            // Compatibility path transaction: TWO renames, NOT an atomic pointer swap.
            // A crash between renames leaves live absent and backup preserved (health fails closed).
            val hadLive = live.exists()
            if (hadLive) check(live.renameTo(backup)) { "Cannot preserve previous archive output" }
            if (!output.renameTo(live)) {
                if (hadLive) check(backup.renameTo(live)) { "Activation failed; previous output retained at ${backup.name}" }
                error("Archive activation failed; previous output restored")
            }
            // Verified staging and its receipt move together. Never erase backup on failed activation.
            if (hadLive) backup.deleteRecursively()
        } finally { stage.deleteRecursively() }
    }

    /** Downloads (with resume) then verifies size/checksum; on mismatch deletes and retries once. */
    private fun attemptDownloadAndVerify(
        file: ModelFile,
        target: File,
        modelId: String,
        index: Int,
        total: Int,
        listener: ProgressListener?,
        shouldCancel: () -> Boolean
    ): Boolean {
        // attemptDownload verifies the staging file before any activation; retry only proven corruption.
        if (attemptDownload(file, target, modelId, index, total, listener, shouldCancel)) return true
        if (lastFailureReason != "Staging integrity mismatch" || shouldCancel()) return false
        return attemptDownload(file, target, modelId, index, total, listener, shouldCancel)
    }

    private fun verifyAfterDownload(target: File, file: ModelFile): Boolean {
        if (!target.isFile || target.length() == 0L) return false
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
        listener: ProgressListener?,
        shouldCancel: () -> Boolean
    ): Boolean {
        val temp = File(target.parentFile, "${target.name}.part")
        // Safety: a file.name with a subpath (e.g. nested under a dir) needs its parent created.
        target.parentFile?.mkdirs()
        val existingBytes = if (temp.exists()) temp.length() else 0L

        if (file.sizeBytes > 0 && existingBytes > file.sizeBytes) {
            Logger.w("ModelInstaller: deleting oversized .part for ${file.name}")
            temp.delete()
            return false
        }

        // If a prior run finished downloading but crashed before the .part→final rename, the temp
        // is already complete. When we can verify that (sizeBytes known and reached), skip the
        // network and commit directly — otherwise the server's 416 "Range Not Satisfiable" would
        // make re-install fail forever.
        if (file.sizeBytes > 0 && existingBytes >= file.sizeBytes) {
            Logger.i("ModelInstaller: ${file.name} .part already complete ($existingBytes bytes); committing")
            return verifyAndCommit(temp, target, file)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(file.url, existingBytes)
            val code = conn.responseCode
            if (code == 416 && existingBytes > 0) {
                // Server says the range is unsatisfiable — we already hold everything it would send.
                // Only safe to commit when we can verify completeness; otherwise bail rather than
                // risk committing a partial file with no integrity fields to verify against.
                if (file.sizeBytes > 0 && existingBytes >= file.sizeBytes) {
                    Logger.i("ModelInstaller: HTTP 416 for ${file.name}; .part complete, committing")
                    return verifyAndCommit(temp, target, file)
                }
                Logger.w("ModelInstaller: HTTP 416 for ${file.name}; cannot verify completeness, failing")
                return false
            }
            if (code !in 200..299) {
                if (code == 408 || code == 429 || code in 500..599) {
                    lastFailureRetryable = true
                    lastFailureReason = "Temporary HTTP $code while downloading ${file.name}; partial data preserved"
                }
                Logger.w("ModelInstaller: HTTP $code for ${file.name}")
                return false
            }
            val resumeSupported = code == 206
            if (resumeSupported && existingBytes > 0) {
                val actualStart = conn.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore('-')
                    ?.toLongOrNull()
                if (actualStart != existingBytes) {
                    Logger.w("ModelInstaller: Content-Range start $actualStart did not match local .part $existingBytes")
                    return false
                }
            }
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
                        if (shouldCancel()) {
                            lastFailureReason = "Download cancelled; partial data preserved for resume"
                            return false
                        }
                        out.write(buffer, 0, read)
                        written += read
                        listener?.onProgress(
                            modelId, index, total, file.name,
                            written, reportedTotal.takeIf { it > 0 } ?: written
                        )
                    }
                }
                // Ensure the complete .part reaches storage before the atomic activation rename.
                out.fd.sync()
            }

            verifyAndCommit(temp, target, file)
        } catch (e: Exception) {
            if (isTransientNetworkFailure(e)) {
                lastFailureRetryable = true
                lastFailureReason = "Network interrupted while downloading ${file.name}; partial data preserved"
            }
            Logger.e("ModelInstaller: download error for ${file.name}", e)
            false
        } finally {
            // Always release the connection — previously a mid-stream exception leaked it.
            runCatching { conn?.disconnect() }
        }
    }

    private fun isTransientNetworkFailure(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any {
            it is java.net.SocketTimeoutException ||
                it is java.net.ConnectException ||
                it is java.net.UnknownHostException ||
                it is java.net.SocketException ||
                it is java.io.EOFException
        }

    private fun openConnection(url: String, existingBytes: Long): HttpURLConnection {
        var current = URL(url)
        val original = current
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = false
                if (existingBytes > 0) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            val code = connection.responseCode
            if (code !in REDIRECT_CODES) return connection
            if (redirectCount >= MAX_REDIRECTS) {
                connection.disconnect()
                throw java.io.IOException("Too many redirects")
            }
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    throw java.io.IOException("Redirect without Location")
                }
            val next = URL(current, location)
            val trusted = if (original.protocol.equals("https", true)) {
                next.protocol.equals("https", true)
            } else {
                // Plain HTTP is retained only for local unit-test servers and existing non-E4B
                // manifests; it may not cross hosts or downgrade a trusted request.
                next.protocol.equals(original.protocol, true) && next.host == original.host
            }
            connection.disconnect()
            if (!trusted) throw java.io.IOException("Refusing untrusted redirect to ${next.protocol}://${next.host}")
            current = next
        }
        throw java.io.IOException("Redirect resolution failed")
    }

    private fun storagePreflight(file: ModelFile, target: File): String? {
        if (file.sizeBytes <= 0L) return null
        val parent = target.parentFile ?: return "Cannot resolve storage directory for ${file.name}"
        val part = File(parent, "${file.name}.part")
        val localPart = part.length().coerceAtMost(file.sizeBytes)
        val remaining = (file.sizeBytes - localPart).coerceAtLeast(0L)
        val replacementHeadroom = file.sizeBytes
        val requiredAvailable = remaining + replacementHeadroom + STORAGE_RESERVE_BYTES
        val available = runCatching { StatFs(parent.absolutePath).availableBytes }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: parent.usableSpace
        return if (available < requiredAvailable) {
            "Insufficient storage for ${file.name}: required ${formatBytes(requiredAvailable)}, available ${formatBytes(available)}"
        } else null
    }

    private fun formatBytes(bytes: Long): String = "%.2f GiB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))

    private fun verifyAndCommit(temp: File, target: File, file: ModelFile): Boolean {
        if (!verifyAfterDownload(temp, file)) {
            lastFailureReason = "Staging integrity mismatch"
            temp.delete()
            return false
        }
        return commitTemp(temp, target)
    }

    /** Same-filesystem atomic rename only: never delete or copy over the previous target. */
    private fun commitTemp(temp: File, target: File): Boolean = try {
        if (!temp.renameTo(target)) {
            Logger.w("ModelInstaller: atomic activation failed for ${target.name}; previous target retained")
            false
        } else true
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
        val seen = mutableSetOf<String>()
        fun destination(name: String): File {
            require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') &&
                !name.contains('\n') && !name.contains('\t') && !name.contains(':') &&
                name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe archive path" }
            require(seen.add(name.trimEnd('/'))) { "Duplicate archive entry" }
            val out = File(destFolder, name)
            require(out.canonicalPath.startsWith(destFolder.canonicalPath + File.separator))
            return out
        }
        fun write(out: File, input: InputStream) {
            check(out.parentFile!!.isDirectory || out.parentFile!!.mkdirs())
            require(!out.exists()) { "Archive entry collision" }
            FileOutputStream(out).use { input.copyTo(it); it.fd.sync() }
        }
        val name = archiveFile.name.lowercase()
        if (name.endsWith(".zip")) {
            // Central-directory Unix mode is necessary: streaming java.util.zip hides symlinks.
            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val mode = entry.unixMode and 0xF000
                    require(!entry.isUnixSymlink && (mode == 0 || mode == 0x8000 || (mode == 0x4000 && entry.isDirectory))) { "Unsupported ZIP entry type" }
                    require(zip.canReadEntryData(entry))
                    val out = destination(entry.name)
                    if (entry.isDirectory) check(out.isDirectory || out.mkdirs())
                    else zip.getInputStream(entry).use { write(out, it) }
                }
            }
            return
        }
        val raw = archiveFile.inputStream().buffered()
        val decompressed = when {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> BZip2CompressorInputStream(raw)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(raw)
            else -> raw
        }
        TarArchiveInputStream(decompressed).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                require(!entry.isSymbolicLink && !entry.isLink && (entry.isDirectory || entry.isFile) && !entry.isSparse) { "Unsupported TAR entry type" }
                require(tar.canReadEntryData(entry))
                val out = destination(entry.name)
                if (entry.isDirectory) check(out.isDirectory || out.mkdirs()) else write(out, tar)
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
        private const val MAX_REDIRECTS = 5
        private const val STORAGE_RESERVE_BYTES = 512L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
