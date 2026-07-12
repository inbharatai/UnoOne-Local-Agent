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

@Serializable
data class LanguagePackManifest(
    val manifestVersion: Int,
    val packs: List<LanguagePackDescriptor>,
    val manifestSignature: String = "",
    val signatureAlgorithm: String = "Ed25519"
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
