package com.unoone.agent.core.model

/**
 * UnoOne V2 has one on-device planning brain: Gemma 4 E4B.
 *
 * The product intentionally exposes a single accuracy-first model profile. Deterministic Android
 * handlers still execute common phone actions without model inference; E4B is reserved for
 * ambiguity, conversation and bounded multi-step planning.
 */
enum class BrainModelId { GEMMA_4_E4B }

/** Model family used by prompt construction. */
enum class ModelFamily { GEMMA_4 }

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
    val description: String
)

/** Single source of truth for the Gemma 4 E4B runtime contract. */
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
        description = "UnoOne's sole accuracy-first local planning brain. Common phone actions remain deterministic; Gemma 4 E4B handles conversation, ambiguity and bounded agent planning through LiteRT-LM with schema validation, safety checks and execution verification."
    )

    val all: List<BrainModelSpec> = listOf(GEMMA_4_E4B)
    val defaultProfile: BrainModelSpec = GEMMA_4_E4B

    fun byId(id: BrainModelId): BrainModelSpec = GEMMA_4_E4B

    fun byManifestId(manifestId: String): BrainModelSpec? =
        GEMMA_4_E4B.takeIf { manifestId == it.manifestId }

    fun byFolder(folder: String): BrainModelSpec? =
        GEMMA_4_E4B.takeIf { folder == it.modelFolder }

    /** Older persisted model identifiers are intentionally migrated to the sole E4B brain. */
    fun resolveOrDefault(manifestId: String?): BrainModelSpec = GEMMA_4_E4B
}
