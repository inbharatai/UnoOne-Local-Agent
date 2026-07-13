package com.unoone.agent.modelmanager

import kotlinx.serialization.Serializable

/**
 * Model kind as declared in `models_manifest.json`. Matches the folder convention used by
 * [ModelManager] and the [com.unoone.agent.storage.entity.ModelMetadataEntity] `modelType` field.
 */
@Serializable
enum class ModelType { llm, asr, tts, vad, punctuation, ocr }

/** Preferred compute backend. `any` lets the planner fall back GPU→CPU (see GemmaPlanner). */
@Serializable
enum class ModelBackend { gpu, cpu, any }

/**
 * A single file that belongs to a model. Multi-file models are described as separate entries so
 * [ModelInstaller] can download each with independent resume and integrity state.
 *
 * Public release artifacts must have exact `sha256` and `sizeBytes`. Empty integrity fields are
 * reserved for visibly unqualified/non-downloadable development descriptors and must never be
 * published as production-approved artifacts.
 *
 * Asset-backed files use [asset] instead of a network URL. Archive entries are extracted into
 * [extractsTo] and health checks verify the extracted directory after the archive is removed.
 */
@Serializable
data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val archive: Boolean = false,
    val asset: String? = null,
    val extractsTo: String? = null
)

/** Descriptor for one installable model below the app-private models root. */
@Serializable
data class ModelDescriptor(
    val id: String,
    val folder: String,
    val type: ModelType,
    val version: String,
    val minRamMb: Int = 0,
    val backend: ModelBackend = ModelBackend.any,
    val defaultLanguage: String = "en",
    val files: List<ModelFile>
)

/**
 * Bundled model catalogue shipped inside the APK.
 *
 * Its trust boundary is the signed APK. Every downloaded artifact is then independently verified by
 * exact size and SHA-256. Public distribution additionally uses the signed release catalogue. A
 * second blank-key/API-dependent signature mechanism is intentionally not retained.
 */
@Serializable
data class ModelManifest(
    val manifestVersion: Int,
    val models: List<ModelDescriptor>
) {
    fun find(id: String): ModelDescriptor? = models.firstOrNull { it.id == id }
    fun findByFolder(folder: String): ModelDescriptor? = models.firstOrNull { it.folder == folder }
}
