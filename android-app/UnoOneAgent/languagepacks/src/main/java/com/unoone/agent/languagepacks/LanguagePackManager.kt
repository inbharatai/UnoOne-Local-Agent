package com.unoone.agent.languagepacks

import android.content.Context
import com.unoone.agent.modelmanager.ModelInstaller
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.storage.dao.ModelMetadataDao

/**
 * High-level language installer built on the existing verified model installer.
 *
 * A language pack is a dependency graph, not a second archive format. Shared ASR/VAD artifacts are
 * installed once and retained while any other installed pack references them.
 */
class LanguagePackManager(
    context: Context,
    modelMetadataDao: ModelMetadataDao? = null,
    private val catalogLoader: LanguagePackCatalogLoader = LanguagePackCatalogLoader()
) {

    private val appContext = context.applicationContext
    private val modelManager = ModelManager(appContext, modelMetadataDao)

    fun catalog(): LanguagePackManifest = catalogLoader.load(appContext)

    fun find(packId: String): LanguagePackDescriptor? = catalog().find(packId)

    suspend fun state(packId: String): LanguagePackState? {
        val descriptor = find(packId) ?: return null
        if (descriptor.requiredModelIds.isEmpty()) {
            return LanguagePackState(
                descriptor = descriptor,
                installed = false,
                healthy = false,
                verified = false,
                missingModelIds = emptyList(),
                unhealthyModelIds = emptyList(),
                unverifiedModelIds = emptyList()
            )
        }

        val missing = mutableListOf<String>()
        val unhealthy = mutableListOf<String>()
        val unverified = mutableListOf<String>()

        for (modelId in descriptor.requiredModelIds) {
            val model = modelManager.findModel(modelId)
            if (model == null) {
                missing += modelId
                continue
            }
            val health = modelManager.modelHealth(modelId)
            when {
                !health.healthy && health.missing.isNotEmpty() -> missing += modelId
                !health.healthy -> unhealthy += modelId
                !health.verified -> unverified += modelId
            }
        }

        val installed = missing.isEmpty() && unhealthy.isEmpty()
        return LanguagePackState(
            descriptor = descriptor,
            installed = installed,
            healthy = installed,
            verified = installed && unverified.isEmpty(),
            missingModelIds = missing,
            unhealthyModelIds = unhealthy,
            unverifiedModelIds = unverified
        )
    }

    suspend fun states(): List<LanguagePackState> =
        catalog().packs.mapNotNull { state(it.id) }

    suspend fun install(
        packId: String,
        onProgress: ((modelId: String, percent: Int, message: String) -> Unit)? = null
    ): LanguagePackOperationResult {
        val pack = find(packId)
            ?: return LanguagePackOperationResult.Failure("Unknown language pack: $packId")
        if (!pack.downloadable) {
            return LanguagePackOperationResult.Failure(
                "${pack.displayName} is listed as ${pack.status}; no qualified downloadable models are configured."
            )
        }
        if (pack.requiredModelIds.isEmpty()) {
            return LanguagePackOperationResult.Failure("${pack.displayName} has no qualified model dependencies.")
        }

        for ((index, modelId) in pack.requiredModelIds.withIndex()) {
            val existing = modelManager.modelHealth(modelId)
            if (existing.healthy) {
                onProgress?.invoke(modelId, 100, "Already installed: $modelId")
                continue
            }

            val result = modelManager.installModel(modelId) { id, _, _, file, downloaded, total ->
                val percent = if (total > 0) {
                    (downloaded * 100 / total).toInt().coerceIn(0, 100)
                } else 0
                onProgress?.invoke(
                    id,
                    percent,
                    "${pack.displayName}: model ${index + 1}/${pack.requiredModelIds.size} · $file"
                )
            }
            if (result is ModelInstaller.InstallResult.Failure) {
                return LanguagePackOperationResult.Failure(
                    "Failed to install ${pack.displayName}: ${result.reason}"
                )
            }
        }

        val finalState = state(packId)
        return if (finalState?.healthy == true) {
            LanguagePackOperationResult.Success(
                if (finalState.verified) {
                    "${pack.displayName} installed and verified."
                } else {
                    "${pack.displayName} installed, but one or more artifacts are not hash-verified."
                }
            )
        } else {
            LanguagePackOperationResult.Failure("${pack.displayName} installation did not pass health checks.")
        }
    }

    suspend fun uninstall(packId: String): LanguagePackOperationResult {
        val pack = find(packId)
            ?: return LanguagePackOperationResult.Failure("Unknown language pack: $packId")
        if (pack.required || !pack.removable) {
            return LanguagePackOperationResult.Failure("${pack.displayName} is a required base pack and cannot be removed.")
        }

        val allStates = states()
        val installedOtherPacks = allStates.filter { it.installed && it.descriptor.id != packId }
        val retained = mutableListOf<String>()
        val removed = mutableListOf<String>()

        for (modelId in pack.requiredModelIds) {
            val usedElsewhere = installedOtherPacks.any { other ->
                modelId in other.descriptor.requiredModelIds || modelId in other.descriptor.optionalModelIds
            }
            if (usedElsewhere) {
                retained += modelId
            } else {
                modelManager.uninstallModel(modelId)
                removed += modelId
            }
        }

        val summary = buildString {
            append("Removed ${pack.displayName}")
            if (removed.isNotEmpty()) append(" models: ${removed.joinToString()}")
            if (retained.isNotEmpty()) append(". Shared models retained: ${retained.joinToString()}")
        }
        return LanguagePackOperationResult.Success(summary)
    }
}
