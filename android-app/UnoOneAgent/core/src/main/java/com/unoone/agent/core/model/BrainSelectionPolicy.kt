package com.unoone.agent.core.model

/** Installation is consulted only once, when migrating an absent selection. Never on load failure. */
object BrainSelectionPolicy {
    fun resolve(savedManifestId: String?, verifiedLegacyE4bInstalled: Boolean): BrainModelSpec =
        if (savedManifestId == null && verifiedLegacyE4bInstalled) {
            BrainModelRegistry.GEMMA_4_E4B
        } else {
            BrainModelRegistry.resolveOrDefault(savedManifestId)
        }
}
