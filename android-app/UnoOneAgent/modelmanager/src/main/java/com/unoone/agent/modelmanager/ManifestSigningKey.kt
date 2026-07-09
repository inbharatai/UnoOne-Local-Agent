package com.unoone.agent.modelmanager

/**
 * The compiled-in Ed25519 public key used to verify [ModelManifest.manifestSignature] at load.
 *
 * **Intentionally empty — verification is wired but INACTIVE.** No public key is fabricated. A
 * manifest with a blank `manifestSignature` is accepted exactly as today (SHA-256 per-file integrity
 * only). To activate the signature chain:
 *
 *  1. The publisher generates an Ed25519 keypair via [ManifestSigner] (`--generate-key`).
 *  2. The publisher Base64-encodes the X.509/SubjectPublicKeyInfo public key and pastes it into
 *     [PUBLIC_KEY_BASE64] below (and ships this build).
 *  3. The publisher signs `models_manifest.json` with the private key ([ManifestSigner] `--sign`),
 *     writes the resulting hex into the manifest's `manifestSignature` field, and ships that manifest.
 *
 * Then [ModelManifestLoader] verifies every manifest at load on Android 13+ (platform Ed25519) and
 * rejects a tampered manifest before any install/health path consumes it. Devices below Android 13
 * fall back to SHA-256 (documented limitation — add Bouncy Castle if 28+ coverage is required).
 *
 * Until [isActive] is true, [ModelManifestLoader] skips verification entirely.
 */
object ManifestSigningKey {
    const val ALGORITHM: String = "Ed25519"

    const val PUBLIC_KEY_BASE64: String = ""

    /** True only when a real public key is compiled in. While empty, signature verification is off. */
    fun isActive(): Boolean = PUBLIC_KEY_BASE64.isNotBlank()
}