package com.unoone.agent.core.model

/**
 * On-device LLM "brain" profile identifiers.
 *
 * UnoOne supports more than one Gemma model side-by-side. The active profile is selected by the
 * user (persisted as the manifest id in SharedPreferences) and loaded by [BrainModelRegistry].
 * Gemma 4 E2B is the newer, lighter planning brain; Gemma 3n E4B is the legacy stable fallback.
 */
enum class BrainModelId { GEMMA_4_E2B, GEMMA_3N_E4B }

/**
 * Model family — drives prompt construction in
 * [com.unoone.agent.localbrain.PromptBuilder]. Both families are Gemma chat models and use the
 * same LiteRT-LM `systemInstruction` mechanism, but Gemma 4 E2B gets an enhanced, tool-call-tuned
 * system instruction. Grouping by family (not by free-form strings) keeps model-family checks in
 * one place instead of scattered across the app.
 */
enum class ModelFamily { GEMMA_4, GEMMA_3N }

/**
 * Hardware backend preference for a brain profile. The actual LiteRT-LM `Backend.GPU()` /
 * `Backend.CPU()` mapping lives in [com.unoone.agent.localbrain.GemmaPlanner]; core never depends
 * on LiteRT-LM, so this enum is dependency-free.
 *
 * - [GPU_FIRST]: try GPU, fall back to CPU (the safe default for both Gemma profiles).
 * - [CPU_ONLY]: never attempt GPU (for profiles known to fail on GPU delegates).
 * - [ANY]: equivalent to GPU_FIRST today; reserved for future auto-backend selection.
 */
enum class BackendPreference { GPU_FIRST, CPU_ONLY, ANY }

/**
 * One authoritative specification of a brain model profile. There is exactly one [BrainModelSpec]
 * per [BrainModelId], held in [BrainModelRegistry]. Nothing in the app should hard-code a model
 * folder, file name, context limit, or family string — it should ask the registry for the spec.
 *
 * @property manifestId       Stable id matching the `models_manifest.json` entry (e.g. "gemma-4-e2b").
 * @property modelFolder      On-disk subdirectory under the models root where the `.litertlm` lives.
 *                             The legacy Gemma 3n profile keeps folder "gemma-local" so existing
 *                             installs are not orphaned by the migration.
 * @property fileName         Expected `.litertlm` file name (informational; discovery globs the extension).
 * @property preferredBackend Hardware preference; GemmaPlanner maps this to LiteRT-LM backends.
 * @property minimumRamMb     Floor below which the profile should not be offered.
 * @property recommendedRamMb Comfortable RAM for good latency.
 * @property maximumContextTokens The model's *theoretical* max context window (a cap, not the budget sent).
 * @property defaultContextTokens The mobile budget actually sent for a normal command (see ContextBudget).
 * @property supportsNativeSystemRole Whether LiteRT-LM `ConversationConfig.systemInstruction` is used.
 * @property isLegacy         True for the stable fallback profile (Gemma 3n E4B).
 * @property isDeviceVerified True only once the physical-device matrix in DEVICE_VERIFICATION.md passes.
 * @property experimentalLabel Non-null label (e.g. "Experimental") shown in the UI until device-verified.
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

/**
 * The single source of truth for brain model profiles. Lookup helpers cover id / manifest id /
 * on-disk folder so callers never hand-compare strings.
 *
 * **Default profile is Gemma 3n E4B** (the device-verified legacy fallback) — NOT Gemma 4. Gemma 4
 * E2B is exposed as "Experimental" until the device-verification matrix is populated and shows
 * tool-call accuracy at least as good as Gemma 3n with no safety/memory/thermal regression.
 */
object BrainModelRegistry {

    /**
     * Gemma 4 E2B — the newer, lighter planning brain. Text-only planning configuration, tuned for
     * short mobile interactions and tool calling. Official artifact: `litert-community/gemma-4-E2B-it-litertlm`
     * (Apache 2.0). The manifest ships URL-only (sha256/size left empty) so no hashes are fabricated;
     * integrity is download-completeness until a verified hash is added.
     *
     * `maximumContextTokens` is the model's 128K window — UnoOne never sends this much (see
     * [com.unoone.agent.localbrain.ContextBudget]); the mobile default is [defaultContextTokens].
     */
    val GEMMA_4_E2B: BrainModelSpec = BrainModelSpec(
        id = BrainModelId.GEMMA_4_E2B,
        manifestId = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        modelFamily = ModelFamily.GEMMA_4,
        modelFolder = "gemma-4-e2b",
        fileName = "gemma-4-e2b-it.litertlm",
        fileExtension = ".litertlm",
        preferredBackend = BackendPreference.GPU_FIRST,
        minimumRamMb = 3072,
        recommendedRamMb = 4096,
        maximumContextTokens = 131_072,
        defaultContextTokens = 4_096,
        supportsNativeSystemRole = true,
        isLegacy = false,
        isDeviceVerified = false,
        experimentalLabel = "Experimental",
        description = "Newer, lighter Gemma 4 planning brain. Faster on mobile; not yet device-verified."
    )

    /**
     * Gemma 3n E4B — the legacy stable fallback. Keeps the `gemma-local` folder so users who already
     * pushed `gemma-3n-e4b.litertlm` to `gemma-local/` keep their installed model after the manifest
     * id migrates from "gemma-local" to "gemma-3n-e4b".
     */
    val GEMMA_3N_E4B: BrainModelSpec = BrainModelSpec(
        id = BrainModelId.GEMMA_3N_E4B,
        manifestId = "gemma-3n-e4b",
        displayName = "Gemma 3n E4B",
        modelFamily = ModelFamily.GEMMA_3N,
        modelFolder = "gemma-local",
        fileName = "gemma-3n-e4b.litertlm",
        fileExtension = ".litertlm",
        preferredBackend = BackendPreference.GPU_FIRST,
        minimumRamMb = 4096,
        recommendedRamMb = 6144,
        maximumContextTokens = 32_768,
        defaultContextTokens = 4_096,
        supportsNativeSystemRole = true,
        isLegacy = true,
        isDeviceVerified = true,
        experimentalLabel = null,
        description = "Legacy stable fallback brain. Device-verified; keeps the gemma-local folder."
    )

    /** All profiles, recommended-first. */
    val all: List<BrainModelSpec> = listOf(GEMMA_4_E2B, GEMMA_3N_E4B)

    /**
     * The default active profile. Remains Gemma 3n E4B until the Gemma 4 device matrix passes —
     * changing this is a device-verification decision, not a code decision.
     */
    val defaultProfile: BrainModelSpec = GEMMA_3N_E4B

    fun byId(id: BrainModelId): BrainModelSpec = all.first { it.id == id }

    /**
     * Lookup by manifest id. Accepts current ids ("gemma-3n-e4b", "gemma-4-e2b") and the legacy
     * "gemma-local" id (mapped to Gemma 3n E4B via [LEGACY_MANIFEST_ID_ALIASES]), so a selection or
     * on-disk metadata saved before the id migration keeps resolving to the right profile.
     */
    fun byManifestId(manifestId: String): BrainModelSpec? =
        all.firstOrNull { it.manifestId == manifestId } ?: LEGACY_MANIFEST_ID_ALIASES[manifestId]

    fun byFolder(folder: String): BrainModelSpec? = all.firstOrNull { it.modelFolder == folder }

    /**
     * Resolve a persisted manifest id back to a spec, falling back to the default profile on
     * blank/unknown ids. Never returns null: a garbage persisted value recovers to the device-verified
     * default (Gemma 3n E4B) rather than breaking the agent.
     */
    fun resolveOrDefault(manifestId: String?): BrainModelSpec =
        manifestId?.takeIf { it.isNotBlank() }?.let { byManifestId(it) } ?: defaultProfile

    /**
     * Manifest ids from older UnoOne releases that map to a current profile. "gemma-local" was the
     * pre-migration id for what is now "gemma-3n-e4b" (same on-disk folder); persisted selections
     * referencing it must keep resolving to the Gemma 3n profile instead of silently switching.
     */
    private val LEGACY_MANIFEST_ID_ALIASES: Map<String, BrainModelSpec> = mapOf(
        "gemma-local" to GEMMA_3N_E4B
    )
}