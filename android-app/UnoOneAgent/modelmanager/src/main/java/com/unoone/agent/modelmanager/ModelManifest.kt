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
 * A single file that belongs to a model. Multi-file models (transducer encoder/decoder/joiner/tokens,
 * VITS model/tokens/espeak-ng-data) are described as separate entries so [ModelInstaller] can
 * download each with its own resume/checksum state.
 *
 * - `sha256` / `sizeBytes`: optional strict-integrity fields. Empty/zero ⇒ the installer skips that
 *   particular check (still validates download completeness via the HTTP response and, for
 *   archives, extraction success). Fill them in for a model variant you ship to enable verification.
 * - `archive`: when true the downloaded file is a ZIP extracted into the model folder then deleted
 *   (used for directory-shaped assets like espeak-ng-data).
 */
@Serializable
data class ModelFile(
    val name: String,
    val url: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val archive: Boolean = false
)

/**
 * Descriptor for one installable model. `id` is the stable key persisted to
 * [com.unoone.agent.storage.entity.ModelMetadataEntity]; `folder` is the on-disk subdirectory under
 * the models root.
 */
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

@Serializable
data class ModelManifest(
    val manifestVersion: Int,
    val models: List<ModelDescriptor>
) {
    fun find(id: String): ModelDescriptor? = models.firstOrNull { it.id == id }
    fun findByFolder(folder: String): ModelDescriptor? = models.firstOrNull { it.folder == folder }
}