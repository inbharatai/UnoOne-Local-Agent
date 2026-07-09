package com.unoone.agent.modelmanager

import android.content.Context
import android.os.Build
import com.unoone.agent.core.util.Logger
import kotlinx.serialization.json.Json

/**
 * Parses and caches the bundled `models_manifest.json` asset. The parse path is split out as
 * [parse] so it is unit-testable without an Android [Context] (tests feed a JSON string).
 *
 * When [ManifestSigningKey] is active and the manifest carries a `manifestSignature`, the loaded
 * manifest is Ed25519-verified against the canonical bytes before it is cached — a tampered manifest
 * is rejected (treated as empty, so no install proceeds). See [verifySignature]. This is wired but
 * inactive today (no compiled-in public key); the existing unsigned manifest is accepted as before.
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
        val parsed = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            Logger.e("ModelManifestLoader: failed to read asset $ASSET_NAME", e)
            return empty().also { cached = it }
        }
        val manifest = if (verifySignature(parsed)) parsed else empty()
        cached = manifest
        return manifest
    }

    fun find(context: Context, id: String): ModelDescriptor? = load(context).find(id)

    /**
     * Ed25519 verification gate. Returns true (accept) when verification is inactive (no compiled-in
     * public key, or the manifest carries no signature) — i.e. today's behavior. When active, verifies
     * the signature on Android 13+ (platform Ed25519) and returns false on a bad signature; on older
     * devices it logs and accepts (documented SHA-256-only fallback — add Bouncy Castle for 28+).
     */
    private fun verifySignature(manifest: ModelManifest): Boolean {
        if (!ManifestSigningKey.isActive() || manifest.manifestSignature.isBlank()) return true
        if (Build.VERSION.SDK_INT < 33) {
            Logger.w("ModelManifestLoader: Ed25519 verify needs Android 13+ (have ${Build.VERSION.SDK_INT}); " +
                "accepting manifest hash-only")
            return true
        }
        val ok = ManifestSignatureVerifier.verify(
            ManifestSignatureVerifier.canonicalBytes(manifest),
            manifest.manifestSignature,
            ManifestSigningKey.PUBLIC_KEY_BASE64
        )
        if (!ok) Logger.e("ModelManifestLoader: manifest signature INVALID — rejecting (treating as empty)")
        return ok
    }

    private fun empty() = ModelManifest(manifestVersion = 1, models = emptyList())

    companion object {
        const val ASSET_NAME = "models_manifest.json"
    }
}