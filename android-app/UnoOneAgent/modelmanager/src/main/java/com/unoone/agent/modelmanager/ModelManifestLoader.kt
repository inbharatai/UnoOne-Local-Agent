package com.unoone.agent.modelmanager

import android.content.Context
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json

/**
 * Parses and caches the bundled `models_manifest.json` asset. The parse path is split out as
 * [parse] so it is unit-testable without an Android [Context] (tests feed a JSON string).
 */
class ModelManifestLoader {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: ModelManifest? = null

    /** Parses a manifest JSON string. Pure / no I/O — used by tests and [load]. */
    fun parse(jsonString: String): ModelManifest =
        json.decodeFromString(ModelManifest.serializer(), jsonString)

    /** Loads and caches the manifest asset, returning an empty manifest (not null) on read error. */
    fun load(context: Context): ModelManifest {
        cached?.let { return it }
        val manifest = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            Logger.e("ModelManifestLoader: failed to read asset $ASSET_NAME", e)
            ModelManifest(manifestVersion = 1, models = emptyList())
        }
        cached = manifest
        return manifest
    }

    fun find(context: Context, id: String): ModelDescriptor? = load(context).find(id)

    companion object {
        const val ASSET_NAME = "models_manifest.json"
    }
}