package com.unoone.agent.core.model

/** Selectable on-device planning profiles; existing E4B selections are preserved. */
enum class BrainModelId { GEMMA_4_E4B, GEMMA_4_E2B, QWEN3_5_2B }

/** Model family used by prompt construction. */
enum class ModelFamily { GEMMA_4, QWEN3_5 }

/** Runtime format; never dispatch an MNN config to LiteRT-LM. */
enum class BrainRuntime { LITERT_LM, MNN }

/** Hardware backend preference. LiteRT-LM backend mapping lives in `:localbrain`. */
enum class BackendPreference { GPU_FIRST, CPU_ONLY, ANY }

/** Developer qualification override. AUTO uses only a recorded-qualified backend. */
enum class BackendQualificationChoice { AUTO, CPU, GPU }

/**
 * Authoritative specification of the UnoOne planning brain.
 *
 * Integrity, performance and device support are deliberately not inferred from a download URL.
 * `isDeviceVerified` may only become true after the physical-device matrix is completed and the
 * result is committed to the repository.
 */
data class BrainModelSpec(
    val id: BrainModelId,
    val manifestId: String,
    val displayName: String,
    val modelFamily: ModelFamily,
    val modelFolder: String,
    val fileName: String,
    val fileExtension: String,
    val preferredBackend: BackendPreference,
    val minimumRamMb: Int,
    val recommendedRamMb: Int,
    val maximumContextTokens: Int,
    val defaultContextTokens: Int,
    val supportsNativeSystemRole: Boolean,
    val isLegacy: Boolean,
    val isDeviceVerified: Boolean,
    val experimentalLabel: String?,
    val description: String,
    val runtime: BrainRuntime = BrainRuntime.LITERT_LM
)

/** Single source of truth for the selectable Gemma 4 runtime contracts. */
object BrainModelRegistry {

    val GEMMA_4_E4B: BrainModelSpec = BrainModelSpec(
        id = BrainModelId.GEMMA_4_E4B,
        manifestId = "gemma-4-e4b",
        displayName = "Gemma 4 E4B",
        modelFamily = ModelFamily.GEMMA_4,
        modelFolder = "brain/gemma-4-e4b",
        fileName = "gemma-4-E4B-it.litertlm",
        fileExtension = ".litertlm",
        // AUTO stays on the conservative CPU baseline until device/hash/build qualification records
        // prove another backend meets the same strict accuracy and stability gates.
        preferredBackend = BackendPreference.ANY,
        minimumRamMb = 8_192,
        recommendedRamMb = 12_288,
        maximumContextTokens = 32_768,
        // Accuracy does not require wasting the full theoretical context window on a phone. Start
        // with the same bounded context used by published mobile measurements; device qualification
        // may raise this only after memory, latency and thermal evidence is recorded.
        defaultContextTokens = 2_048,
        supportsNativeSystemRole = true,
        isLegacy = false,
        isDeviceVerified = false,
        experimentalLabel = "Xiaomi 14 qualification required",
        description = "UnoOne's retained accuracy-first local planning brain. Common phone actions remain deterministic; Gemma 4 E4B handles conversation, ambiguity and bounded agent planning through LiteRT-LM with schema validation, safety checks and execution verification."
    )

    // RAM/context values are conservative application policy, not device qualification claims.
    val GEMMA_4_E2B: BrainModelSpec = GEMMA_4_E4B.copy(
        id = BrainModelId.GEMMA_4_E2B,
        manifestId = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        modelFolder = "brain/gemma-4-e2b",
        fileName = "gemma-4-E2B-it.litertlm",
        description = "Default local planning profile using the pinned LiteRT-LM E2B artifact. Physical-device, image and grounding qualification is required; E4B remains selectable."
    )

    val QWEN3_5_2B: BrainModelSpec = GEMMA_4_E2B.copy(
        id = BrainModelId.QWEN3_5_2B,
        manifestId = "qwen3.5-2b-mnn",
        displayName = "Qwen 3.5 2B (EXPERIMENTAL)",
        modelFamily = ModelFamily.QWEN3_5,
        modelFolder = "brain/qwen3.5-2b-mnn",
        fileName = "config.json",
        fileExtension = ".json",
        preferredBackend = BackendPreference.CPU_ONLY,
        maximumContextTokens = 4_096,
        defaultContextTokens = 2_048,
        experimentalLabel = "EXPERIMENTAL — native/device qualification required",
        description = "Pinned 4-bit MNN export. Opt-in only; no device performance or vision qualification claimed. E4B remains recoverable.",
        runtime = BrainRuntime.MNN
    )

    val all: List<BrainModelSpec> = listOf(GEMMA_4_E2B, GEMMA_4_E4B, QWEN3_5_2B)
    val defaultProfile: BrainModelSpec = GEMMA_4_E2B

    fun byId(id: BrainModelId): BrainModelSpec = all.first { it.id == id }

    fun byManifestId(manifestId: String): BrainModelSpec? =
        all.firstOrNull { it.manifestId == manifestId }

    fun byFolder(folder: String): BrainModelSpec? =
        all.firstOrNull { it.modelFolder == folder }

    /** New/unknown selections use E2B; persisted E4B is never silently migrated. */
    fun resolveOrDefault(manifestId: String?): BrainModelSpec =
        manifestId?.let(::byManifestId) ?: defaultProfile
}
