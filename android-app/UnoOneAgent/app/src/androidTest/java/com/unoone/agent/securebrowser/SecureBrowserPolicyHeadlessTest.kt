package com.unoone.agent.securebrowser

import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real on-device, HEADLESS Secure-Browser / PageAgent gate (DEVICE_VERIFICATION §4). The interactive
 * WebView navigation / form / dropdown / upload-takeover gates require a live HTTPS page and a human
 * at the screen (no screenshots available to this runner) and are recorded separately as manual gates.
 * This test covers the parts that ARE provable headlessly on the physical device:
 *
 *  - [BrowserDomainPolicy.evaluate] — exact-origin HTTPS allow-list: blocks unapproved origin,
 *    subdomain, cleartext HTTP, localhost, IP-literal and `javascript:` fragments (the same logic
 *    the JVM BrowserDomainPolicyTest covers, but proven here on the device's ART, not the JVM).
 *  - [BrowserSafetyPolicy.evaluate] — payments blocked, credentials/OTP/CAPTCHA/legal → user
 *    takeover, final submit / file → confirm, ordinary input → allow (device ART).
 *  - The PageAgent runtime asset is packaged and byte-authentic in the INSTALLED app: reads
 *    `page-agent/unoone-page-agent.js` from the app's AssetManager, asserts non-blank, exact size
 *    exact checked-in size/hash and the `UnoOnePageAgentRuntime` entry symbol — i.e. the
 *    runtime the WebView would inject is present and intact (§4 "PageAgent runtime initializes from
 *    the packaged asset", asset-level).
 *
 * Run: am instrument -e class com.unoone.agent.securebrowser.SecureBrowserPolicyHeadlessTest ...
 */
class SecureBrowserPolicyHeadlessTest {

    private val allowed = setOf("https://unigurus.com", "https://uniassist.ai", "https://testsprep.in")

    @Test
    fun domainPolicyAllowsExactApprovedHttpsAndBlocksEverythingElse() {
        val policy = BrowserDomainPolicy(allowed)

        // approved exact origin (any path) → Allow
        val ok = policy.evaluate("https://unigurus.com/some/path?q=1")
        assertTrue("approved origin must be allowed", ok is NavigationDecision.Allow)
        assertEquals("https://unigurus.com", (ok as NavigationDecision.Allow).origin)

        // subdomain NOT implicitly trusted
        assertTrue("subdomain must be blocked", policy.evaluate("https://www.unigurus.com") is NavigationDecision.Block)
        // unapproved origin
        assertTrue("unapproved origin must be blocked", policy.evaluate("https://evil.com") is NavigationDecision.Block)
        // cleartext HTTP
        assertTrue("HTTP must be blocked", policy.evaluate("http://unigurus.com") is NavigationDecision.Block)
        // localhost + IP-literal
        assertTrue("localhost must be blocked", policy.evaluate("https://localhost") is NavigationDecision.Block)
        assertTrue("IP literal must be blocked", policy.evaluate("https://192.168.1.1") is NavigationDecision.Block)
        assertTrue("IPv6 literal must be blocked", policy.evaluate("https://[::1]") is NavigationDecision.Block)
        // javascript: fragment
        assertTrue("javascript fragment must be blocked",
            policy.evaluate("https://unigurus.com#javascript:alert(1)") is NavigationDecision.Block)
        // userinfo
        assertTrue("userinfo credentials must be blocked",
            policy.evaluate("https://user:pass@unigurus.com") is NavigationDecision.Block)

        // isAllowedOrigin mirror
        assertTrue(policy.isAllowedOrigin("https://unigurus.com"))
        assertFalse(policy.isAllowedOrigin("https://evil.com"))
        assertEquals(allowed, policy.origins())
    }

    @Test
    fun prototypeNavigationAllowsPublicHttpsButRetainsTransportBoundaries() {
        val policy = BrowserDomainPolicy(allowed)
        val mode = BrowserNavigationMode.PROTOTYPE_PUBLIC_HTTPS

        assertTrue(policy.evaluate("https://forms.inbharat.ai/a-form", mode) is NavigationDecision.Allow)
        assertTrue(policy.evaluate("https://apply.inbharat.ai/step/2", mode) is NavigationDecision.Allow)
        assertTrue(policy.evaluate("http://forms.inbharat.ai", mode) is NavigationDecision.Block)
        assertTrue(policy.evaluate("javascript:alert(1)", mode) is NavigationDecision.Block)
        assertTrue(policy.evaluate("https://user:pass@forms.inbharat.ai", mode) is NavigationDecision.Block)
        assertTrue(policy.evaluate("https://localhost", mode) is NavigationDecision.Block)
        assertTrue(policy.evaluate("https://192.168.0.1", mode) is NavigationDecision.Block)
    }

    @Test
    fun safetyPolicyBlocksPaymentsAndTakesOverCredentialsOtpCaptchaLegal() {
        // payments → Block (never autonomous)
        val pay = BrowserSafetyPolicy.evaluate("make_payment", "pay now with card")
        assertTrue("payment must be blocked", pay is BrowserActionDecision.Block)

        // credentials → UserTakeover
        assertTrue("password must be user takeover",
            BrowserSafetyPolicy.evaluate("enter_password", "the password field") is BrowserActionDecision.UserTakeover)
        // OTP → UserTakeover
        assertTrue("OTP must be user takeover",
            BrowserSafetyPolicy.evaluate("enter_otp", "verification code") is BrowserActionDecision.UserTakeover)
        // CAPTCHA → UserTakeover
        assertTrue("CAPTCHA must be user takeover",
            BrowserSafetyPolicy.evaluate("solve_captcha", "i am not a robot") is BrowserActionDecision.UserTakeover)
        // legal acceptance → UserTakeover
        assertTrue("legal acceptance must be user takeover",
            BrowserSafetyPolicy.evaluate("accept_terms", "accept terms and declare that") is BrowserActionDecision.UserTakeover)
        // final submit → Confirm (native confirmation)
        assertTrue("final submit must require confirmation",
            BrowserSafetyPolicy.evaluate("submit_form", "final submit") is BrowserActionDecision.Confirm)
        // file upload → Confirm
        assertTrue("file transfer must require confirmation",
            BrowserSafetyPolicy.evaluate("upload_file", "upload file") is BrowserActionDecision.Confirm)
        // ordinary input → Allow
        assertTrue("ordinary input must be allowed",
            BrowserSafetyPolicy.evaluate("input_text", "enter your name") is BrowserActionDecision.Allow)
        // unknown action → Confirm (never silent allow)
        assertTrue("unknown action must require confirmation",
            BrowserSafetyPolicy.evaluate("totally_unknown_xyz", "") is BrowserActionDecision.Confirm)
    }

    @Test
    fun pageAgentRuntimeAssetIsPackagedAndByteAuthentic() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = context.assets.open("page-agent/unoone-page-agent.js").use { it.readBytes() }

        assertTrue("PageAgent runtime asset must be non-blank", bytes.isNotEmpty())
        assertEquals("PageAgent asset exact size", 196197, bytes.size)

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "PageAgent asset SHA-256 must match the laptop-built bundle (Phase 2)",
            "d798e06e95e3cbab1f71aac4498d428bde76ec1eec6e9c13b99852a6b2cf6369",
            sha
        )

        val text = String(bytes, Charsets.UTF_8)
        assertTrue(
            "bundle must contain the UnoOnePageAgentRuntime entry symbol the WebView injects",
            text.contains("UnoOnePageAgentRuntime")
        )
    }
}
