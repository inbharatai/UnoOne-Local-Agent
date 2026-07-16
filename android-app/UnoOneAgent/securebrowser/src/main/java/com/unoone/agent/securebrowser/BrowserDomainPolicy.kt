package com.unoone.agent.securebrowser

import java.net.IDN
import java.net.URI

sealed class NavigationDecision {
    data class Allow(val normalizedUrl: String, val origin: String) : NavigationDecision()
    data class Block(val reason: String) : NavigationDecision()
}

/** Remote-navigation scope. Standard stays allow-listed; prototype mode admits public HTTPS. */
enum class BrowserNavigationMode { APPROVED_ONLY, PROTOTYPE_PUBLIC_HTTPS }

/**
 * Navigation policy for PageAgent automation.
 *
 * Standard mode permits only explicitly supplied exact HTTPS origins. Explicit Prototype/Off may
 * admit other public HTTPS hosts. Both modes reject localhost, `.local`, IP literals, cleartext
 * HTTP, embedded credentials and executable/non-web schemes before WebView navigation or bridge
 * exposure. Standard does not implicitly trust subdomains.
 */
class BrowserDomainPolicy(allowedOrigins: Set<String>) {

    private val allowed: Set<String> = allowedOrigins.mapNotNull { normalizeOrigin(it) }.toSet()

    fun evaluate(
        rawUrl: String,
        mode: BrowserNavigationMode = BrowserNavigationMode.APPROVED_ONLY
    ): NavigationDecision {
        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return NavigationDecision.Block("Malformed URL")
        }

        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return NavigationDecision.Block("Only HTTPS navigation is allowed")
        }
        if (uri.userInfo != null) return NavigationDecision.Block("URLs containing credentials are blocked")
        if (uri.host.isNullOrBlank()) return NavigationDecision.Block("URL has no valid host")
        if (uri.fragment?.startsWith("javascript", ignoreCase = true) == true) {
            return NavigationDecision.Block("Executable URL fragments are blocked")
        }

        val asciiHost = try { IDN.toASCII(uri.host.lowercase()) } catch (_: Exception) {
            return NavigationDecision.Block("Invalid internationalized host")
        }
        if (
            asciiHost == "localhost" || asciiHost.endsWith(".localhost") ||
            asciiHost == "unoone.local-form" || asciiHost.endsWith(".local") ||
            isIpLiteral(asciiHost)
        ) {
            return NavigationDecision.Block("Local and IP-literal hosts are blocked")
        }

        val port = if (uri.port == -1 || uri.port == 443) -1 else uri.port
        val origin = buildString {
            append("https://")
            append(asciiHost)
            if (port != -1) append(":$port")
        }
        if (mode == BrowserNavigationMode.APPROVED_ONLY && origin !in allowed) {
            return NavigationDecision.Block("Origin is not approved for UnoOne automation: $origin")
        }

        val normalized = URI(
            "https",
            null,
            asciiHost,
            port,
            uri.rawPath?.ifBlank { "/" } ?: "/",
            uri.rawQuery,
            uri.rawFragment
        ).toASCIIString()
        return NavigationDecision.Allow(normalized, origin)
    }

    fun isAllowedOrigin(origin: String): Boolean = normalizeOrigin(origin) in allowed

    /** True only for a syntactically valid public HTTPS origin accepted by prototype navigation. */
    fun isPublicHttpsOrigin(origin: String): Boolean =
        evaluate(origin, BrowserNavigationMode.PROTOTYPE_PUBLIC_HTTPS) is NavigationDecision.Allow

    fun origins(): Set<String> = allowed

    private fun normalizeOrigin(value: String): String? {
        val uri = try { URI(value.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        val host = try { IDN.toASCII(uri.host.lowercase()) } catch (_: Exception) { return null }
        if (
            host == "localhost" || host.endsWith(".localhost") ||
            host == "unoone.local-form" || host.endsWith(".local") ||
            isIpLiteral(host)
        ) return null
        val port = if (uri.port == -1 || uri.port == 443) -1 else uri.port
        return if (port == -1) "https://$host" else "https://$host:$port"
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6 literal
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotBlank() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255
        }
    }
}
