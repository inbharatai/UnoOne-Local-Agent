package com.unoone.agent.modelmanager

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

/**
 * Exercises [ModelInstaller] against a tiny in-process HTTP server built on plain [ServerSocket]
 * (no external deps, no `com.sun.net.httpserver` — that package is not in Android's Java SE
 * subset). This tests resume, checksum verification, corrupt-recovery, and zip extraction for real
 * over actual sockets, without a network or a device.
 */
class ModelInstallerTest {

    private lateinit var baseDir: File
    private lateinit var modelDir: String
    private lateinit var installer: ModelInstaller

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("unoone-install-test").toFile()
        modelDir = baseDir.resolve("models").absolutePath
        installer = ModelInstaller(modelDir, dao = null)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun downloadsAndVerifiesChecksum() {
        val content = "hello world\n".toByteArray()
        val sha = sha256(content)
        val server = MiniHttpServer(content, supportRange = false).apply { start() }
        try {
            val descriptor = descriptor("m", "file.bin", server.url("file.bin"), sha, content.size.toLong())
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            val target = File(modelDir, "m/file.bin")
            assertTrue(target.exists())
            assertArrayEquals(content, target.readBytes())
            assertFalse(File(modelDir, "m/file.bin.part").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun resumesFromPartialFile() {
        val full = "the quick brown fox jumps over the lazy dog".toByteArray()
        val firstChunk = full.copyOfRange(0, 20) // "the quick brown fox"
        val server = MiniHttpServer(full, supportRange = true).apply { start() }
        try {
            // Pre-seed a .part file with the first chunk to simulate an interrupted download.
            File(modelDir, "m").mkdirs()
            File(modelDir, "m/big.bin.part").writeBytes(firstChunk)

            val descriptor = descriptor("m", "big.bin", server.url("big.bin"), "", 0L)
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            val target = File(modelDir, "m/big.bin")
            assertArrayEquals(full, target.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun corruptRecoveryFailsWhenServerServesWrongSize() {
        // Manifest declares sizeBytes = 50 but the server only ever serves 5 bytes ("short").
        val server = MiniHttpServer("short".toByteArray(), supportRange = false).apply { start() }
        try {
            val descriptor = descriptor("m", "bad.bin", server.url("bad.bin"), "", 50L)
            val result = runBlocking { installer.install(descriptor, listener = null) }
            assertTrue(result is ModelInstaller.InstallResult.Failure)
            assertFalse(File(modelDir, "m/bad.bin").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun extractsZipArchiveAndDeletesArchive() {
        val zipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("inner.txt"))
                zos.write("inside the zip".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val server = MiniHttpServer(zipBytes, supportRange = false).apply { start() }
        try {
            val descriptor = ModelDescriptor(
                id = "m", folder = "m", type = ModelType.tts, version = "v",
                minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
                files = listOf(ModelFile("arch.zip", server.url("arch.zip"), "", 0L, archive = true))
            )
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            assertTrue(File(modelDir, "m/inner.txt").exists())
            assertEquals("inside the zip", File(modelDir, "m/inner.txt").readText())
            // The archive file itself is deleted after extraction.
            assertFalse(File(modelDir, "m/arch.zip").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun idempotentSkipWhenFileAlreadyValid() {
        val content = "already here".toByteArray()
        val sha = sha256(content)
        val server = MiniHttpServer(content, supportRange = false).apply { start() }
        try {
            File(modelDir, "m").mkdirs()
            File(modelDir, "m/skip.bin").writeBytes(content)

            val descriptor = descriptor("m", "skip.bin", server.url("skip.bin"), sha, content.size.toLong())
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            assertArrayEquals(content, File(modelDir, "m/skip.bin").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun reDownloadsWhenExistingFileIsTruncatedOrCorrupt() {
        // A file is present but its checksum does not match the manifest (e.g. truncated by a crash).
        // The installer must detect it is unhealthy and re-download rather than skip.
        val correct = "the correct full content".toByteArray()
        val sha = sha256(correct)
        val server = MiniHttpServer(correct, supportRange = false).apply { start() }
        try {
            File(modelDir, "m").mkdirs()
            File(modelDir, "m/recheck.bin").writeBytes("truncated/wrong".toByteArray())

            val descriptor = descriptor("m", "recheck.bin", server.url("recheck.bin"), sha, correct.size.toLong())
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            assertArrayEquals(correct, File(modelDir, "m/recheck.bin").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun doesNotTrustZeroByteFileWhenNoIntegrityDeclared() {
        // Regression for the empty-file guard in fileAlreadyValid: a 0-byte file on disk with NO
        // declared size/sha must NOT be treated as valid (it may be a truncated download). The
        // installer must re-download instead of skipping. Without the guard this test fails because
        // fileAlreadyValid returns true for a present file whose sha is blank, and the 0-byte file
        // is kept as-is.
        val content = "the real content".toByteArray()
        val server = MiniHttpServer(content, supportRange = false).apply { start() }
        try {
            File(modelDir, "m").mkdirs()
            // Pre-place a 0-byte (truncated) file with no integrity fields declared.
            File(modelDir, "m/zero.bin").writeBytes(ByteArray(0))

            val descriptor = descriptor("m", "zero.bin", server.url("zero.bin"), "", 0L)
            val result = runBlocking { installer.install(descriptor) }
            assertTrue(result is ModelInstaller.InstallResult.Success)
            assertArrayEquals(content, File(modelDir, "m/zero.bin").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun commitsCompletePartFileWithoutNetwork() {
        // Regression for the complete-.part commit guard + HTTP 416 recovery: a prior run finished
        // downloading but crashed before the .part→final rename. When the manifest declares a size
        // and the .part already holds that many bytes, the installer must commit directly without
        // touching the network (otherwise a server returning 416 "Range Not Satisfiable" would make
        // re-install fail forever). No server is started here — if the code path tried the network
        // the test would fail instead of passing.
        val content = "fully downloaded already".toByteArray()
        File(modelDir, "m").mkdirs()
        // Pre-place a complete .part; the final file does NOT exist yet.
        File(modelDir, "m/done.bin.part").writeBytes(content)

        val descriptor = descriptor("m", "done.bin", "http://invalid.invalid/done.bin", "", content.size.toLong())
        val result = runBlocking { installer.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        assertArrayEquals(content, File(modelDir, "m/done.bin").readBytes())
        assertFalse(File(modelDir, "m/done.bin.part").exists())
    }

    @Test
    fun installsArchiveFromAssetAndExtracts() {
        // An asset-backed archive (espeak-ng-data.zip pattern): copied from the asset reader, then
        // extracted into the model folder, then the zip deleted. No HTTP server is started — if the
        // asset path weren't used the test would fail to find the bytes.
        val zipBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("espeak-ng-data/af_dict"))
                zos.write("af-data".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("espeak-ng-data/phondata"))
                zos.write("phon".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        val assets = mapOf("espeak-ng-data.zip" to zipBytes)
        val assetInstaller = ModelInstaller(modelDir, dao = null) { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }
        val descriptor = ModelDescriptor(
            id = "tts", folder = "tts", type = ModelType.tts, version = "v",
            minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
            files = listOf(
                ModelFile(
                    "espeak-ng-data.zip", url = "", sha256 = "", sizeBytes = zipBytes.size.toLong(),
                    archive = true, asset = "espeak-ng-data.zip"
                )
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        // Archive deleted after extraction.
        assertFalse(File(modelDir, "tts/espeak-ng-data.zip").exists())
        // Extracted directory present with contents (rooted at espeak-ng-data/).
        assertTrue(File(modelDir, "tts/espeak-ng-data").isDirectory)
        assertEquals("af-data", File(modelDir, "tts/espeak-ng-data/af_dict").readText())
        assertEquals("phon", File(modelDir, "tts/espeak-ng-data/phondata").readText())
    }

    @Test
    fun installsPlainFileFromAssetWithChecksum() {
        val content = "asset payload".toByteArray()
        val sha = sha256(content)
        val assets = mapOf("payload.bin" to content)
        val assetInstaller = ModelInstaller(modelDir, dao = null) { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }
        val descriptor = ModelDescriptor(
            id = "m", folder = "m", type = ModelType.llm, version = "v",
            minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
            files = listOf(
                ModelFile(
                    "payload.bin", url = "", sha256 = sha, sizeBytes = content.size.toLong(),
                    archive = false, asset = "payload.bin"
                )
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        assertArrayEquals(content, File(modelDir, "m/payload.bin").readBytes())
    }

    @Test
    fun failsWhenAssetMissing() {
        // An asset-backed file whose asset is absent must fail cleanly (not crash, not silently skip).
        val assetInstaller = ModelInstaller(modelDir, dao = null) { _ -> null }
        val descriptor = ModelDescriptor(
            id = "m", folder = "m", type = ModelType.llm, version = "v",
            minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
            files = listOf(
                ModelFile("payload.bin", url = "", sha256 = "", sizeBytes = 0, archive = false, asset = "payload.bin")
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Failure)
        assertFalse(File(modelDir, "m/payload.bin").exists())
    }

    @Test
    fun skipsAssetArchiveWhenAlreadyExtracted() {
        // Idempotent: a previously extracted archive directory means no asset copy is needed and the
        // asset reader is never invoked.
        File(modelDir, "tts/espeak-ng-data").mkdirs()
        File(modelDir, "tts/espeak-ng-data/phondata").writeText("already")
        var reads = 0
        val assetInstaller = ModelInstaller(modelDir, dao = null) { _ ->
            reads++
            ByteArrayInputStream("should-not-be-used".toByteArray())
        }
        val descriptor = ModelDescriptor(
            id = "tts", folder = "tts", type = ModelType.tts, version = "v",
            minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
            files = listOf(
                ModelFile("espeak-ng-data.zip", url = "", sha256 = "", sizeBytes = 0, archive = true, asset = "espeak-ng-data.zip")
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        assertEquals(0, reads) // asset reader never invoked
        assertEquals("already", File(modelDir, "tts/espeak-ng-data/phondata").readText())
    }

    @Test
    fun extractsTarBz2ArchiveWithExtractsTo() {
        // The whisper-tiny pattern: a .tar.bz2 whose top directory ("sherpa-onnx-whisper-tiny")
        // differs from the archive name and whose double extension (".tar.bz2") breaks the
        // strip-last-extension fallback. `extractsTo` names the real top dir so the installer
        // extracts and later health-checks the right path.
        val tarBytes = tarBz2(
            mapOf(
                "sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx" to "ENC".toByteArray(),
                "sherpa-onnx-whisper-tiny/tiny-tokens.txt" to "TOK".toByteArray()
            )
        )
        val assets = mapOf("sherpa-onnx-whisper-tiny.tar.bz2" to tarBytes)
        val assetInstaller = ModelInstaller(modelDir, dao = null) { name ->
            assets[name]?.let { ByteArrayInputStream(it) }
        }
        val descriptor = ModelDescriptor(
            id = "sherpa-asr-whisper", folder = "sherpa-asr-whisper", type = ModelType.asr,
            version = "whisper-tiny-int8", minRamMb = 0, backend = ModelBackend.cpu,
            defaultLanguage = "multi",
            files = listOf(
                ModelFile(
                    name = "sherpa-onnx-whisper-tiny.tar.bz2",
                    url = "", sha256 = "", sizeBytes = tarBytes.size.toLong(),
                    archive = true, asset = "sherpa-onnx-whisper-tiny.tar.bz2",
                    extractsTo = "sherpa-onnx-whisper-tiny"
                )
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        // Archive deleted after extraction.
        assertFalse(File(modelDir, "sherpa-asr-whisper/sherpa-onnx-whisper-tiny.tar.bz2").exists())
        // Extracted files sit under the extractsTo top directory.
        val top = File(modelDir, "sherpa-asr-whisper/sherpa-onnx-whisper-tiny")
        assertTrue(top.isDirectory)
        assertEquals("ENC", File(top, "tiny-encoder.int8.onnx").readText())
        assertEquals("TOK", File(top, "tiny-tokens.txt").readText())
    }

    @Test
    fun skipsTarBz2ArchiveWhenAlreadyExtractedViaExtractsTo() {
        // Idempotent: when the extractsTo directory already exists with content, the installer must
        // skip without invoking the asset reader. This exercises archiveAlreadyExtracted with
        // extractsTo (the strip-last-extension fallback would look for "pkg.tar" and never match).
        val top = File(modelDir, "sherpa-asr-whisper/sherpa-onnx-whisper-tiny").apply { mkdirs() }
        File(top, "tiny-encoder.int8.onnx").writeText("already")
        var reads = 0
        val assetInstaller = ModelInstaller(modelDir, dao = null) { _ ->
            reads++
            ByteArrayInputStream("should-not-be-used".toByteArray())
        }
        val descriptor = ModelDescriptor(
            id = "sherpa-asr-whisper", folder = "sherpa-asr-whisper", type = ModelType.asr,
            version = "v", minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "multi",
            files = listOf(
                ModelFile(
                    name = "pkg.tar.bz2", url = "", sha256 = "", sizeBytes = 0,
                    archive = true, asset = "pkg.tar.bz2", extractsTo = "sherpa-onnx-whisper-tiny"
                )
            )
        )
        val result = runBlocking { assetInstaller.install(descriptor) }
        assertTrue(result is ModelInstaller.InstallResult.Success)
        assertEquals(0, reads) // asset reader never invoked
        assertEquals("already", File(top, "tiny-encoder.int8.onnx").readText())
    }

    // ---- helpers ----

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Builds a tar.bz2 with the given `path → bytes` entries (commons-compress, no native deps). */
    private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bos).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                entries.forEach { (name, data) ->
                    val entry = TarArchiveEntry(name)
                    entry.size = data.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    private fun descriptor(id: String, file: String, url: String, sha: String, size: Long): ModelDescriptor =
        ModelDescriptor(
            id = id, folder = id, type = ModelType.llm, version = "v",
            minRamMb = 0, backend = ModelBackend.cpu, defaultLanguage = "en",
            files = listOf(ModelFile(file, url, sha, size, archive = false))
        )

    /** Minimal HTTP/1.1 server over a plain socket serving [body], optionally honouring Range. */
    private class MiniHttpServer(private val body: ByteArray, private val supportRange: Boolean) {
        private val server = ServerSocket(0)
        private val thread = Thread { runServer() }
        private var stopped = false

        fun start() { thread.start() }
        fun stop() {
            stopped = true
            try { server.close() } catch (_: IOException) {}
        }
        fun url(path: String): String = "http://localhost:${server.localPort}/$path"

        private fun runServer() {
            try {
                while (!stopped && !server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    try { handle(socket) } catch (_: IOException) {} finally { try { socket.close() } catch (_: IOException) {} }
                }
            } catch (_: IOException) {}
        }

        private fun handle(socket: Socket) {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            // request line
            reader.readLine() ?: return
            val headers = HashMap<String, String>()
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                line = reader.readLine()
            }
            val range = headers["range"]
            val out: OutputStream = socket.getOutputStream()
            if (supportRange && range != null && range.startsWith("bytes=")) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                if (from in 0 until body.size) {
                    val slice = body.copyOfRange(from, body.size)
                    writeResponse(out, "206 Partial Content", slice,
                        extra = "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\n")
                } else {
                    writeResponse(out, "416 Range Not Satisfiable", ByteArray(0), extra = "")
                }
            } else {
                writeResponse(out, "200 OK", body, extra = "")
            }
            out.flush()
        }

        private fun writeResponse(out: OutputStream, status: String, payload: ByteArray, extra: String) {
            val header = "HTTP/1.1 $status\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n$extra\r\n"
            out.write(header.toByteArray())
            if (payload.isNotEmpty()) out.write(payload)
        }
    }
}