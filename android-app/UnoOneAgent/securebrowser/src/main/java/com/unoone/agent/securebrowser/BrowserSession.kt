package com.unoone.agent.securebrowser

import java.security.SecureRandom
import java.util.UUID

/** Immutable identity and mutable lifecycle state for one Secure Browser automation task. */
data class BrowserSession(
    val id: String = UUID.randomUUID().toString(),
    val nonce: String = generateNonce(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val allowedOrigins: Set<String>,
    var activeOrigin: String? = null,
    var active: Boolean = true
) {
    fun close() {
        active = false
        activeOrigin = null
    }

    companion object {
        private val random = SecureRandom()

        private fun generateNonce(): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
