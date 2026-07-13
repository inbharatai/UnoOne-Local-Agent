package com.unoone.agent.modelmanager

import android.content.Context
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json

/**
 * Parses and caches the bundled `models_manifest.json` asset.
 *
 * The manifest is part of the signed APK. Downloaded artifacts are independently checked by exact
 * size and SHA-256, while public releases are governed by the signed distribution catalogue. Read or
 * parse failure returns an empty manifest so no model installation proceeds.
 */
class ModelManifestLoader {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: ModelManifest? = null

    /** Parses a manifest JSON string. Pure / no I/O — used by tests and [load]. */
    fun parse(jsonString: String): ModelManifest =
        json.decodeFromString(ModelManifest.serializer(), jsonString)

    /** Loads and caches the manifest asset, returning an empty manifest on any read/parse error. */
    fun load(context: Context): ModelManifest {
        cached?.let { return it }
        val manifest = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            Logger.e("ModelManifestLoader: failed to read asset $ASSET_NAME", e)
            empty()
        }
        cached = manifest
        return manifest
    }

    fun find(context: Context, id: String): ModelDescriptor? = load(context).find(id)

    fun clearCache() {
        cached = null
    }

    private fun empty() = ModelManifest(manifestVersion = 2, models = emptyList())

    companion object {
        const val ASSET_NAME = "models_manifest.json"
    }
}
