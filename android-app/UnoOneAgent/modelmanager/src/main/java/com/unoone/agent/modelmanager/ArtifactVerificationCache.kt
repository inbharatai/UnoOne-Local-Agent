package com.unoone.agent.modelmanager

import android.content.Context
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class VerifiedArtifactRecord(
    val modelId: String,
    val artifactId: String,
    val expectedSha256: String,
    val expectedSize: Long,
    val canonicalPath: String,
    val actualSize: Long,
    val lastModified: Long,
    val manifestVersion: Int,
    val modelVersion: String,
    val verified: Boolean,
    val verifiedAtMs: Long
)

interface VerificationRecordStore {
    fun read(modelId: String, artifactId: String): VerifiedArtifactRecord?
    fun write(record: VerifiedArtifactRecord)
    fun remove(modelId: String)
}

internal class PreferencesVerificationRecordStore(context: Context) : VerificationRecordStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false }

    override fun read(modelId: String, artifactId: String): VerifiedArtifactRecord? {
        val raw = prefs.getString(cacheKey(modelId, artifactId), null) ?: return null
        return runCatching { json.decodeFromString(VerifiedArtifactRecord.serializer(), raw) }
            .onFailure {
                prefs.edit().remove(cacheKey(modelId, artifactId)).apply()
                Logger.w("Artifact verification cache: discarded corrupt record for $modelId")
            }
            .getOrNull()
    }

    override fun write(record: VerifiedArtifactRecord) {
        prefs.edit().putString(
            cacheKey(record.modelId, record.artifactId),
            json.encodeToString(VerifiedArtifactRecord.serializer(), record)
        ).apply()
    }

    override fun remove(modelId: String) {
        val edit = prefs.edit().remove(modelId)
        prefs.all.keys.filter { it.startsWith("${modelId.length}:$modelId:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun cacheKey(modelId: String, artifactId: String) = "${modelId.length}:$modelId:$artifactId"

    companion object { private const val PREFS = "verified_model_artifacts_v1" }
}

data class ArtifactVerificationResult(
    val verified: Boolean,
    val fromCache: Boolean,
    val actualSha256: String? = null
)

/** Exact SHA verification with a metadata-bound cache and one verification per model at a time. */
class ArtifactVerifier(
    private val store: VerificationRecordStore,
    private val hasher: suspend (File) -> String? = { file -> sha256(file) }
) {
    /**
     * Persists the proof produced by [ModelInstaller] after its mandatory full SHA-256 check.
     * This never marks a missing, partial, incorrectly sized, or non-canonical file as verified.
     */
    suspend fun recordVerified(
        descriptor: ModelDescriptor,
        fileDescriptor: ModelFile,
        file: File,
        manifestVersion: Int
    ): Boolean {
        val mutex = locks.computeIfAbsent(descriptor.id) { Mutex() }
        return mutex.withLock {
            if (!isCandidateValid(fileDescriptor, file)) {
                store.remove(descriptor.id)
                return@withLock false
            }
            val canonical = runCatching { file.canonicalPath }.getOrNull()
                ?: return@withLock false
            store.write(
                VerifiedArtifactRecord(
                    modelId = descriptor.id,
                    artifactId = fileDescriptor.name,
                    expectedSha256 = fileDescriptor.sha256.lowercase(),
                    expectedSize = fileDescriptor.sizeBytes,
                    canonicalPath = canonical,
                    actualSize = file.length(),
                    lastModified = file.lastModified(),
                    manifestVersion = manifestVersion,
                    modelVersion = descriptor.version,
                    verified = true,
                    verifiedAtMs = System.currentTimeMillis()
                )
            )
            true
        }
    }

    suspend fun verify(
        descriptor: ModelDescriptor,
        fileDescriptor: ModelFile,
        file: File,
        manifestVersion: Int,
        force: Boolean = false
    ): ArtifactVerificationResult {
        val mutex = locks.computeIfAbsent(descriptor.id) { Mutex() }
        return mutex.withLock {
            if (!isCandidateValid(fileDescriptor, file)) {
                store.remove(descriptor.id)
                return@withLock ArtifactVerificationResult(false, false)
            }
            val canonical = runCatching { file.canonicalPath }.getOrNull()
                ?: return@withLock ArtifactVerificationResult(false, false)
            val current = VerifiedArtifactRecord(
                modelId = descriptor.id,
                    artifactId = fileDescriptor.name,
                expectedSha256 = fileDescriptor.sha256.lowercase(),
                expectedSize = fileDescriptor.sizeBytes,
                canonicalPath = canonical,
                actualSize = file.length(),
                lastModified = file.lastModified(),
                manifestVersion = manifestVersion,
                modelVersion = descriptor.version,
                verified = true,
                verifiedAtMs = 0L
            )
            val cached = if (force) null else store.read(descriptor.id, fileDescriptor.name)
            if (cached != null && cached.verified && cached.sameIdentity(current)) {
                return@withLock ArtifactVerificationResult(true, true, cached.expectedSha256)
            }

            val actualHash = hasher(file)?.lowercase()
            val verified = actualHash == fileDescriptor.sha256.lowercase()
            if (verified) {
                store.write(current.copy(verifiedAtMs = System.currentTimeMillis()))
            } else {
                store.remove(descriptor.id)
            }
            ArtifactVerificationResult(verified, false, actualHash)
        }
    }

    fun invalidate(modelId: String) = store.remove(modelId)

    private fun isCandidateValid(expected: ModelFile, actual: File): Boolean =
        !actual.name.endsWith(".part") &&
            actual.isFile &&
            expected.sizeBytes > 0L &&
            expected.sha256.matches(Regex("^[a-fA-F0-9]{64}$")) &&
            actual.length() == expected.sizeBytes

    private fun VerifiedArtifactRecord.sameIdentity(other: VerifiedArtifactRecord): Boolean =
        modelId == other.modelId &&
            artifactId == other.artifactId &&
            expectedSha256 == other.expectedSha256 &&
            expectedSize == other.expectedSize &&
            canonicalPath == other.canonicalPath &&
            actualSize == other.actualSize &&
            lastModified == other.lastModified &&
            manifestVersion == other.manifestVersion &&
            modelVersion == other.modelVersion

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()

        private suspend fun sha256(file: File): String? = withContext(Dispatchers.IO) {
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
    }
}
