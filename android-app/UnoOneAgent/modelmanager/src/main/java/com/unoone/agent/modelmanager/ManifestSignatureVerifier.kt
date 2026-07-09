package com.unoone.agent.modelmanager

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Verifies an Ed25519 signature over the canonical form of a [ModelManifest], so a tampered manifest
 * (e.g. an attacker swapping a model file + its declared SHA-256 together) is rejected at load —
 * beyond the per-file SHA-256 check in [ModelInstaller].
 *
 * **No new dependency.** Uses platform `java.security` EdDSA, which exists on the JDK 17 unit-test JVM
 * (so the full sign→verify round-trip + tamper rejection are JVM-testable with no Android) and on
 * Android at runtime only on API 33+ (platform Ed25519). Devices below API 33 fall back to SHA-256
 * only — see [ModelManifestLoader]. When 28+ coverage is required, Bouncy Castle can be added; not
 * now, to avoid a ~6 MB APK cost for an inactive feature.
 *
 * **Canonicalization** is the contract between signer and verifier: both parse the manifest, blank
 * [ModelManifest.manifestSignature], and re-serialize with a fixed `Json` config (`encodeDefaults`,
 * no pretty-print). kotlinx.serialization emits fields in declaration order deterministically, so the
 * bytes are reproducible on both sides. The signature is over those UTF-8 bytes.
 *
 * **No fabricated keys or signatures.** [ManifestSigningKey] holds the compiled-in public key, which
 * is empty here — verification is therefore wired-but-inactive (a blank manifest signature is
 * accepted exactly as today). The publisher activates it by embedding a real public key and shipping a
 * manifest signed via [ManifestSigner].
 */
object ManifestSignatureVerifier {

    private val canonicalJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * The deterministic bytes a signature covers: the manifest with `manifestSignature` blanked,
     * re-serialized with a fixed config. Signer and verifier MUST use this exact function.
     */
    fun canonicalBytes(manifest: ModelManifest): ByteArray {
        val blanked = manifest.copy(manifestSignature = "")
        val canonical = canonicalJson.encodeToString(ModelManifest.serializer(), blanked)
        return canonical.toByteArray(Charsets.UTF_8)
    }

    /**
     * Returns true iff [signatureHex] is a valid Ed25519 signature over [canonicalBytes] for the
     * public key [publicKeyBase64] (X.509/SubjectPublicKeyInfo, Base64). Returns false (never throws)
     * on any crypto failure, blank inputs, or unsupported algorithm — the loader treats false as
     * "reject manifest".
     */
    fun verify(canonicalBytes: ByteArray, signatureHex: String, publicKeyBase64: String): Boolean {
        if (signatureHex.isBlank() || publicKeyBase64.isBlank()) return false
        return try {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val pubKey = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
            val sig = java.security.Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(canonicalBytes)
            sig.verify(hexToBytes(signatureHex))
        } catch (e: Exception) {
            // NoSuchAlgorithmException/InvalidKeyException on a JVM/Android without Ed25519, bad key,
            // bad sig bytes — all collapse to "do not trust". The loader logs the algorithm-missing
            // case separately (API gate) so older devices are not falsely flagged as tampered.
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) throw IllegalArgumentException("odd-length hex")
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw IllegalArgumentException("non-hex char in signature")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}