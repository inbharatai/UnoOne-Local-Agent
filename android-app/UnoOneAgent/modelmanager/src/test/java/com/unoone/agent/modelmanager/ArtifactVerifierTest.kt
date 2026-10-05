package com.unoone.agent.modelmanager

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ArtifactVerifierTest {
    private lateinit var dir: File
    private lateinit var store: MemoryStore
    private val bytes = "exact-e4b-test-artifact".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private val descriptor get() = ModelDescriptor(
        "gemma-4-e4b", "brain/gemma-4-e4b", ModelType.llm, "revision", files = listOf(fileSpec)
    )
    private val fileSpec get() = ModelFile("gemma-4-E4B-it.litertlm", "https://example.test/model", sha, bytes.size.toLong())

    @Before fun setup() { dir = Files.createTempDirectory("artifact-cache").toFile(); store = MemoryStore() }
    @After fun teardown() { dir.deleteRecursively() }

    @Test fun `first verification hashes and second uses cache`() = runBlocking {
        val calls = AtomicInteger()
        val verifier = verifier(calls)
        val file = modelFile().apply { writeBytes(bytes) }
        assertTrue(verifier.verify(descriptor, fileSpec, file, 3).verified)
        assertTrue(verifier.verify(descriptor, fileSpec, file, 3).fromCache)
        assertEquals(1, calls.get())
    }

    @Test fun `installer proof seeds cache without hashing again`() = runBlocking {
        val calls = AtomicInteger()
        val verifier = verifier(calls)
        val file = modelFile().apply { writeBytes(bytes) }

        assertTrue(verifier.recordVerified(descriptor, fileSpec, file, 3))
        val result = verifier.verify(descriptor, fileSpec, file, 3)

        assertTrue(result.verified)
        assertTrue(result.fromCache)
        assertEquals(0, calls.get())
    }

    @Test fun `size timestamp hash version and path changes invalidate cache`() = runBlocking {
        val calls = AtomicInteger()
        val verifier = verifier(calls)
        val file = modelFile().apply { writeBytes(bytes) }
        assertTrue(verifier.verify(descriptor, fileSpec, file, 3).verified)
        file.setLastModified(file.lastModified() + 2_000)
        assertFalse(verifier.verify(descriptor, fileSpec, file, 3).fromCache)
        val changedManifest = fileSpec.copy(sha256 = "0".repeat(64))
        assertFalse(verifier.verify(descriptor, changedManifest, file, 3).verified)
        assertFalse(verifier.verify(descriptor, fileSpec, file, 4).fromCache)
        file.writeBytes(byteArrayOf(1))
        assertFalse(verifier.verify(descriptor, fileSpec, file, 4).verified)
        assertTrue(calls.get() >= 3)
    }

    @Test fun `part deleted wrong and corrupt cache records fail closed`() = runBlocking {
        val verifier = verifier(AtomicInteger())
        val part = File(dir, "gemma-4-E4B-it.litertlm.part").apply { writeBytes(bytes) }
        assertFalse(verifier.verify(descriptor, fileSpec, part, 3).verified)
        val missing = modelFile()
        assertFalse(verifier.verify(descriptor, fileSpec, missing, 3).verified)
        missing.writeBytes("wrong-but-same-length!!".padEnd(bytes.size).take(bytes.size).toByteArray())
        assertFalse(verifier.verify(descriptor, fileSpec, missing, 3).verified)
        store.records["${descriptor.id}:${fileSpec.name}"] = "corrupt"
        missing.writeBytes(bytes)
        assertTrue(verifier.verify(descriptor, fileSpec, missing, 3).verified)
    }

    @Test fun `concurrent callers perform one hash`() = runBlocking {
        val calls = AtomicInteger()
        val verifier = verifier(calls, delayMs = 30)
        val file = modelFile().apply { writeBytes(bytes) }
        val results = List(8) { async { verifier.verify(descriptor, fileSpec, file, 3) } }.awaitAll()
        assertTrue(results.all { it.verified })
        assertEquals(1, calls.get())
    }

    @Test fun `nine file proofs persist independently force hashes all and uninstall invalidates all`() = runBlocking {
        val calls = AtomicInteger()
        val verifier = verifier(calls)
        val specs = List(9) { fileSpec.copy(name = "artifact-$it") }
        val model = descriptor.copy(id = "qwen-test", files = specs)
        specs.forEach { spec ->
            val file = File(dir, spec.name).apply { writeBytes(bytes) }
            assertTrue(verifier.recordVerified(model, spec, file, 5))
        }
        repeat(2) {
            specs.forEach { assertTrue(verifier.verify(model, it, File(dir, it.name), 5).fromCache) }
        }
        assertEquals(0, calls.get())
        specs.forEach { assertFalse(verifier.verify(model, it, File(dir, it.name), 5, force = true).fromCache) }
        assertEquals(9, calls.get())
        verifier.invalidate(model.id)
        specs.forEach { assertFalse(verifier.verify(model, it, File(dir, it.name), 5).fromCache) }
        assertEquals(18, calls.get())
    }

    private fun modelFile() = File(dir, fileSpec.name)

    private fun verifier(calls: AtomicInteger, delayMs: Long = 0) = ArtifactVerifier(store) { file ->
        calls.incrementAndGet()
        if (delayMs > 0) delay(delayMs)
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    private class MemoryStore : VerificationRecordStore {
        val records = ConcurrentHashMap<String, Any>()
        override fun read(modelId: String, artifactId: String): VerifiedArtifactRecord? = records["$modelId:$artifactId"] as? VerifiedArtifactRecord
        override fun write(record: VerifiedArtifactRecord) { records["${record.modelId}:${record.artifactId}"] = record }
        override fun remove(modelId: String) { records.keys.filter { it == modelId || it.startsWith("$modelId:") }.forEach { records.remove(it) } }
    }
}
