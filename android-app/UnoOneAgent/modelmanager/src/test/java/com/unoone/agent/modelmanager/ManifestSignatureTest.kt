package com.unoone.agent.modelmanager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM round-trip + tamper tests for the manifest signature chain. Uses platform Ed25519 on the
 * JDK 17 test JVM (no Android, no Bouncy Castle). Proves the sign/verify contract that, at runtime on
 * Android 13+, lets [ModelManifestLoader] reject a tampered manifest. No production key is used — a
 * fresh keypair is generated per test.
 */
class ManifestSignatureTest {

    private fun sampleManifest(signature: String = ""): ModelManifest = ModelManifest(
        manifestVersion = 1,
        models = listOf(
            ModelDescriptor(
                id = "gemma-3n-e4b", folder = "gemma-local", type = ModelType.llm,
                version = "gemma-3n-e4b-litertlm", minRamMb = 4096, backend = ModelBackend.any,
                defaultLanguage = "en",
                files = listOf(ModelFile(name = "gemma-3n-e4b.litertlm", url = "https://example.org/g.litertlm"))
            )
        ),
        manifestSignature = signature
    )

    private val manifestJson = """
        {"manifestVersion":1,"models":[
          {"id":"gemma-3n-e4b","folder":"gemma-local","type":"llm","version":"v","minRamMb":4096,
           "backend":"any","defaultLanguage":"en",
           "files":[{"name":"gemma-3n-e4b.litertlm","url":"https://example.org/g.litertlm","sha256":"","sizeBytes":0}]}
        ]}
    """.trimIndent()

    @Test
    fun signedManifestVerifies() {
        val (pub, priv) = ManifestSigner.generateKeyPair()
        val manifest = sampleManifest()
        val canonical = ManifestSignatureVerifier.canonicalBytes(manifest)
        val sig = ManifestSigner.sign(canonical, priv)
        assertTrue("valid signature must verify", ManifestSignatureVerifier.verify(canonical, sig, pub))
    }

    @Test
    fun signManifestOverloadRoundTrips() {
        val (pub, priv) = ManifestSigner.generateKeyPair()
        val sig = ManifestSigner.signManifest(manifestJson, priv)
        val parsed = ModelManifestLoader().parse(manifestJson)
        val canonical = ManifestSignatureVerifier.canonicalBytes(parsed)
        assertTrue(ManifestSignatureVerifier.verify(canonical, sig, pub))
    }

    @Test
    fun tamperedCanonicalBytesRejected() {
        val (pub, priv) = ManifestSigner.generateKeyPair()
        val canonical = ManifestSignatureVerifier.canonicalBytes(sampleManifest())
        val sig = ManifestSigner.sign(canonical, priv)
        val tampered = canonical.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertFalse("tampered content must not verify", ManifestSignatureVerifier.verify(tampered, sig, pub))
    }

    @Test
    fun tamperedSignatureRejected() {
        val (pub, priv) = ManifestSigner.generateKeyPair()
        val canonical = ManifestSignatureVerifier.canonicalBytes(sampleManifest())
        val sig = ManifestSigner.sign(canonical, priv)
        // Flip one hex char of the signature.
        val broken = if (sig[0] == '0') "1" + sig.substring(1) else "0" + sig.substring(1)
        assertFalse(ManifestSignatureVerifier.verify(canonical, broken, pub))
    }

    @Test
    fun wrongPublicKeyRejected() {
        val (_, priv) = ManifestSigner.generateKeyPair()
        val (otherPub, _) = ManifestSigner.generateKeyPair()
        val canonical = ManifestSignatureVerifier.canonicalBytes(sampleManifest())
        val sig = ManifestSigner.sign(canonical, priv)
        assertFalse(ManifestSignatureVerifier.verify(canonical, sig, otherPub))
    }

    @Test
    fun blankSignatureOrKeyDoesNotVerify() {
        val canonical = ManifestSignatureVerifier.canonicalBytes(sampleManifest())
        assertFalse("blank signature must not verify", ManifestSignatureVerifier.verify(canonical, "", "key"))
        assertFalse("blank key must not verify", ManifestSignatureVerifier.verify(canonical, "deadbeef", ""))
    }

    @Test
    fun canonicalBytesAreStableAndSignatureIndependent() {
        // A manifest signed with signature X and the same manifest unsigned must produce identical
        // canonical bytes — the signature field is blanked before hashing.
        val signed = sampleManifest(signature = "abc123")
        val unsigned = sampleManifest(signature = "")
        assertArrayEquals(
            ManifestSignatureVerifier.canonicalBytes(signed),
            ManifestSignatureVerifier.canonicalBytes(unsigned)
        )
        // And deterministic across calls.
        assertArrayEquals(
            ManifestSignatureVerifier.canonicalBytes(unsigned),
            ManifestSignatureVerifier.canonicalBytes(unsigned)
        )
    }

    @Test
    fun manifestWithSignatureFieldParses() {
        val json = manifestJson.removeSuffix("}") +
            ""","manifestSignature":"deadbeef","signatureAlgorithm":"Ed25519"}"""
        val parsed = ModelManifestLoader().parse(json)
        assertEquals("deadbeef", parsed.manifestSignature)
        assertEquals("Ed25519", parsed.signatureAlgorithm)
    }

    @Test
    fun signingKeyIsInactiveByDefault() {
        // No fabricated key compiled in: verification must be off until a real key is embedded.
        assertFalse(ManifestSigningKey.isActive())
        assertEquals("", ManifestSigningKey.PUBLIC_KEY_BASE64)
    }
}