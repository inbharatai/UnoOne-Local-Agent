package com.unoone.agent.languagepacks

import kotlinx.serialization.Serializable

@Serializable
enum class LanguagePackStatus { baseline, planned, beta, stable, deprecated }

@Serializable
data class LanguagePackDescriptor(
    val id: String,
    val languageCode: String,
    val displayName: String,
    val nativeName: String,
    val version: String,
    val status: LanguagePackStatus,
    val requiredModelIds: List<String>,
    val optionalModelIds: List<String> = emptyList(),
    val required: Boolean = false,
    val removable: Boolean = true,
    val downloadable: Boolean = true,
    val minimumRamMb: Int = 0,
    val notes: String = ""
)

/**
 * Bundled language catalogue protected by the signed APK.
 *
 * Publicly distributed language models are independently governed by exact file hashes and the
 * signed release catalogue. Planned packs remain metadata-only and non-downloadable.
 */
@Serializable
data class LanguagePackManifest(
    val manifestVersion: Int,
    val packs: List<LanguagePackDescriptor>
) {
    fun find(id: String): LanguagePackDescriptor? = packs.firstOrNull { it.id == id }
    fun findByLanguageCode(code: String): LanguagePackDescriptor? =
        packs.firstOrNull { it.languageCode.equals(code, ignoreCase = true) }
}

data class LanguagePackState(
    val descriptor: LanguagePackDescriptor,
    val installed: Boolean,
    val healthy: Boolean,
    val verified: Boolean,
    val missingModelIds: List<String>,
    val unhealthyModelIds: List<String>,
    val unverifiedModelIds: List<String>
)

sealed class LanguagePackOperationResult {
    data class Success(val message: String) : LanguagePackOperationResult()
    data class Failure(val message: String) : LanguagePackOperationResult()
}
