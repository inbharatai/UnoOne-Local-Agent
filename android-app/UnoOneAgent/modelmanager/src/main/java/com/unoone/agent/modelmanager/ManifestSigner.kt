package com.unoone.agent.modelmanager

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline signer for [ModelManifest] — the publisher side of the signature chain verified at load by
 * [ManifestSignatureVerifier]. Runs on a desktop JDK (Ed25519 has been in the JDK since 15), so it has
 * no Android minSdk constraint and no device dependency.
 *
 * Usage (CLI entry point in [main]):
 *   - `--generate-key`              → prints a fresh Base64 keypair (paste the public key into
 *     [ManifestSigningKey.PUBLIC_KEY_BASE64]; keep the private key secret).
 *   - `--sign <manifest.json> <privateKey.base64>` → prints the hex signature to paste into the
 *     manifest's `manifestSignature` field.
 *
 * This object is JVM-pure and is round-trip tested ([ManifestSignatureTest]) against
 * [ManifestSignatureVerifier] with no Android — so the sign/verify contract is proven, not assumed.
 * No production key or signature is fabricated by this code.
 */
object ManifestSigner {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Generates a fresh Ed25519 keypair; returns (publicKeyBase64, privateKeyBase64). */
    fun generateKeyPair(): Pair<String, String> {
        val gen = java.security.KeyPairGenerator.getInstance("Ed25519")
        val pair = gen.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(pair.public.encoded) // X.509 SubjectPublicKeyInfo
        val priv = Base64.getEncoder().encodeToString(pair.private.encoded) // PKCS#8
        return pub to priv
    }

    /** Signs the canonical form of [manifestJson] with [privateKeyBase64] (PKCS#8, Base64). */
    fun signManifest(manifestJson: String, privateKeyBase64: String): String {
        val manifest = json.decodeFromString(ModelManifest.serializer(), manifestJson)
        val canonical = ManifestSignatureVerifier.canonicalBytes(manifest)
        return sign(canonical, privateKeyBase64)
    }

    /** Signs arbitrary canonical bytes; returns a lowercase hex signature. */
    fun sign(canonicalBytes: ByteArray, privateKeyBase64: String): String {
        val keyBytes = Base64.getDecoder().decode(privateKeyBase64)
        val privKey = java.security.KeyFactory.getInstance("Ed25519")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initSign(privKey)
        sig.update(canonicalBytes)
        return bytesToHex(sig.sign())
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * CLI entry point. Run from the modelmanager directory on a JDK 15+ host:
     *   `./gradlew :modelmanager:runManifestSigner --args='...'` is NOT wired (no application plugin);
     *   instead run the class directly with `java -cp <modelmanagerClasses> ...` or invoke from a test.
     *   Kept as a documented convenience; the round-trip is proven by [ManifestSignatureTest].
     */
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "--generate-key" -> {
                val (pub, priv) = generateKeyPair()
                println("PUBLIC_KEY_BASE64=$pub")
                println("PRIVATE_KEY_BASE64=$priv  (keep secret)")
            }
            "--sign" -> {
                require(args.size >= 3) { "usage: --sign <manifest.json> <privateKey.base64>" }
                val manifestJson = java.io.File(args[1]).readText()
                val sig = signManifest(manifestJson, args[2])
                println("manifestSignature=$sig")
            }
            else -> {
                println("ManifestSigner — Ed25519 manifest signing tool")
                println("  --generate-key                  print a fresh Base64 keypair")
                println("  --sign <manifest> <privKey>     print the manifest signature hex")
            }
        }
    }
}