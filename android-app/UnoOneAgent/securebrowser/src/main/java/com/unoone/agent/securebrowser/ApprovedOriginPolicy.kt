package com.unoone.agent.securebrowser

/**
 * Single source of truth for the HTTPS origins UnoOne's Secure Browser may automate, plus the
 * friendly-name → approved-origin resolver used by the `secure_browser_task` tool so a blind user
 * can say "open unigurus" instead of typing an HTTPS URL.
 *
 * These are REAL production origins (the InBharat family of sites) — never add a placeholder or
 * fake origin here. The list is referenced by `SecureBrowserViewModel.APPROVED_ORIGINS` so the
 * WebView navigation policy and the tool-level gate agree exactly.
 *
 * The actual WebView navigation still goes through [BrowserDomainPolicy] (cleartext / IP-literal /
 * subdomain / credential blocking); this object only decides whether a proposed origin is one UnoOne
 * may drive at all, and resolves the bare host / friendly spoken forms to the canonical HTTPS origin.
 */
object ApprovedOriginPolicy {

    /** Canonical approved HTTPS origins. One source of truth — referenced by SecureBrowserViewModel. */
    val APPROVED_ORIGINS: Set<String> = setOf(
        "https://unigurus.com",
        "https://www.unigurus.com",
        "https://uniassist.ai",
        "https://www.uniassist.ai",
        "https://testsprep.in",
        "https://www.testsprep.in",
        "https://inbharat.ai",
        "https://www.inbharat.ai"
    )

    /**
     * Friendly / spoken names → canonical origin (no `www`). Keys are matched lowercased against
     * the trimmed token, so "UniGurus", "uni guru", "uni-assist" all resolve. Spoken forms a
     * transcription is likely to emit are listed; if a phrase does not tokenize cleanly it should be
     * added here rather than faked at the model layer.
     */
    private val FRIENDLY_NAMES: Map<String, String> = mapOf(
        "unigurus" to "https://unigurus.com",
        "uni guru" to "https://unigurus.com",
        "uni gurus" to "https://unigurus.com",
        "uniguru" to "https://unigurus.com",
        "uniassist" to "https://uniassist.ai",
        "uni assist" to "https://uniassist.ai",
        "uni-assist" to "https://uniassist.ai",
        "testsprep" to "https://testsprep.in",
        "tests prep" to "https://testsprep.in",
        "tests-prep" to "https://testsprep.in",
        "inbharat" to "https://inbharat.ai",
        "in bharat" to "https://inbharat.ai",
        "in-bharat" to "https://inbharat.ai"
    )

    private val policy = BrowserDomainPolicy(APPROVED_ORIGINS)

    /**
     * Resolve a raw URL or origin string (e.g. "https://unigurus.com/apply?step=1") to its approved
     * origin, or null if not approved / unsafe. Reuses [BrowserDomainPolicy] so the same cleartext /
     * IP / subdomain / credential rules apply as the live WebView path.
     */
    fun resolve(rawUrlOrOrigin: String): String? = when (val decision = policy.evaluate(rawUrlOrOrigin)) {
        is NavigationDecision.Allow -> decision.origin
        is NavigationDecision.Block -> null
    }

    /**
     * Resolve a bare host or friendly spoken name (e.g. "unigurus", "www.unigurus.com",
     * "inbharat.ai") to the canonical approved origin, or null if it does not map to an approved
     * origin. Inserts an implicit "https://" when the input has no scheme.
     */
    fun resolveFriendly(token: String): String? {
        val trimmed = token.trim().removeSuffix("/")
        if (trimmed.isBlank()) return null
        val lowered = trimmed.lowercase()
        FRIENDLY_NAMES[lowered]?.let { return it }
        // Bare host with no scheme → try https://
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return resolve(candidate)
    }

    /**
     * Combined resolver for the `secure_browser_task` tool's `origin` argument: accepts a full URL,
     * a bare origin, a bare host, or a friendly name, returning the canonical approved origin or
     * null if UnoOne may not automate it. This is the only entry point the tool layer should use.
     */
    fun originFor(raw: String): String? = resolveFriendly(raw)

    /**
     * Resolve a public HTTPS target for the explicit Prototype/Off mode. Friendly production names
     * still work, while a full URL keeps its path/query so a spoken command can open a particular
     * form rather than being collapsed to the site root. Cleartext, credentials, localhost,
     * `.local`, the synthetic local-form host and IP literals remain rejected by BrowserDomainPolicy.
     */
    fun prototypeUrlFor(raw: String): String? {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isBlank()) return null
        FRIENDLY_NAMES[trimmed.lowercase()]?.let { return it }
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return when (
            val decision = policy.evaluate(candidate, BrowserNavigationMode.PROTOTYPE_PUBLIC_HTTPS)
        ) {
            is NavigationDecision.Allow -> decision.normalizedUrl
            is NavigationDecision.Block -> null
        }
    }

    /** True iff [raw] resolves to an approved origin. Convenience for safety/audit checks. */
    fun isApproved(raw: String): Boolean = originFor(raw) != null
}
