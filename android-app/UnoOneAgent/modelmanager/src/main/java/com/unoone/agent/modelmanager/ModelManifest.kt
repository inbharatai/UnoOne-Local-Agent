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
 * - `archive`: when true the file is an archive (ZIP, tar.bz2, tar.gz) extracted into the model
 *   folder then deleted (used for directory-shaped assets like espeak-ng-data, or the whisper-tiny
 *   model tarball). Health/install-skip verify the extracted directory rather than the (deleted)
 *   archive. By default the extracted directory is derived by stripping the last extension of
 *   `name` (`<dir>.zip` → `<dir>/`); set [extractsTo] when that convention does not hold — e.g. a
 *   `sherpa-onnx-whisper-tiny.tar.bz2` whose top directory is `sherpa-onnx-whisper-tiny/` (the
 *   `.tar.bz2` double extension and the differing top dir both break the strip-last-extension rule).
 * - `extractsTo`: optional name of the top directory an archive extracts to, used for archive
 *   health and install-skip. When null, falls back to stripping the last extension of `name`.
 * - `asset`: optional name of a file bundled in the app's `assets/`. When set, the installer copies
 *   the asset to `name` instead of downloading `url` (the espeak-ng-data phoneme table ships this
 *   way — ~9 MB, language-independent, stable — avoiding 355 individual HTTP downloads at install
 *   time and making offline TTS work with no network). `url` may be blank for asset-backed files.
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
    val models: List<ModelDescriptor>,
    /**
     * Optional Ed25519 signature over the canonical manifest bytes (the manifest re-serialized with
     * this field blanked). When present AND [com.unoone.agent.modelmanager.ManifestSigningKey] has a
     * compiled-in public key, [ModelManifestLoader] verifies it at load and rejects a tampered
     * manifest. When blank (the default / today), the manifest is accepted as before — SHA-256
     * per-file integrity only. No signature is fabricated here; the field ships empty until the
     * publisher signs a manifest (see [com.unoone.agent.modelmanager.ManifestSigner]).
     */
    val manifestSignature: String = "",
    val signatureAlgorithm: String = "Ed25519"
) {
    fun find(id: String): ModelDescriptor? = models.firstOrNull { it.id == id }
    fun findByFolder(folder: String): ModelDescriptor? = models.firstOrNull { it.folder == folder }
}