package com.unoone.agent.core.model

/**
 * UnoOne V2 has one on-device planning brain: Gemma 4 E2B.
 *
 * Keeping an enum and registry preserves a typed contract across modules without retaining the old
 * dual-profile behaviour. Model selection is no longer a product feature; installation, health,
 * backend and device qualification remain explicit states of this single brain.
 */
enum class BrainModelId { GEMMA_4_E2B }

/** Model family used by prompt construction. */
enum class ModelFamily { GEMMA_4 }

/** Hardware backend preference. LiteRT-LM backend mapping lives in `:localbrain`. */
enum class BackendPreference { GPU_FIRST, CPU_ONLY, ANY }

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

/** Single source of truth for the Gemma 4 E2B runtime contract. */
object BrainModelRegistry {

    val GEMMA_4_E2B: BrainModelSpec = BrainModelSpec(
        id = BrainModelId.GEMMA_4_E2B,
        manifestId = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        modelFamily = ModelFamily.GEMMA_4,
        modelFolder = "brain/gemma-4-e2b",
        fileName = "gemma-4-E2B-it.litertlm",
        fileExtension = ".litertlm",
        preferredBackend = BackendPreference.GPU_FIRST,
        minimumRamMb = 6_144,
        recommendedRamMb = 8_192,
        maximumContextTokens = 32_768,
        defaultContextTokens = 4_096,
        supportsNativeSystemRole = true,
        isLegacy = false,
        isDeviceVerified = false,
        experimentalLabel = "Device qualification required",
        description = "UnoOne's sole local planning brain. The generic Android LiteRT-LM artifact must pass integrity, tool-call, memory, thermal and real-device tests before production release."
    )

    val all: List<BrainModelSpec> = listOf(GEMMA_4_E2B)
    val defaultProfile: BrainModelSpec = GEMMA_4_E2B

    fun byId(id: BrainModelId): BrainModelSpec = GEMMA_4_E2B

    fun byManifestId(manifestId: String): BrainModelSpec? =
        GEMMA_4_E2B.takeIf { manifestId == it.manifestId }

    fun byFolder(folder: String): BrainModelSpec? =
        GEMMA_4_E2B.takeIf { folder == it.modelFolder }

    /** Older persisted values are intentionally normalized to the sole Gemma 4 E2B brain. */
    fun resolveOrDefault(manifestId: String?): BrainModelSpec = GEMMA_4_E2B
}
