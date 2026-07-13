package com.unoone.agent.languagepacks

import android.content.Context
import kotlinx.serialization.json.Json

/** Loads the bundled language-pack catalogue. Remote signed catalogues will layer on later. */
class LanguagePackCatalogLoader {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: LanguagePackManifest? = null

    fun parse(content: String): LanguagePackManifest =
        json.decodeFromString(LanguagePackManifest.serializer(), content)

    fun load(context: Context): LanguagePackManifest {
        cached?.let { return it }
        val manifest = context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        cached = manifest
        return manifest
    }

    fun clearCache() {
        cached = null
    }

    companion object {
        const val ASSET_NAME = "language_packs.json"
    }
}
