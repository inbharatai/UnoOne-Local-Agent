package com.unoone.agent.securebrowser

import org.junit.Assert.*
import org.junit.Test

class NativeTargetPolicyTest {
    @Test fun navigationRevokesOutstandingTarget() {
        val policy = NativeTargetPolicy()
        val epoch = policy.epoch
        assertTrue(policy.matches(epoch, 1, "target", 1, "target"))
        policy.revoke()
        assertFalse(policy.matches(epoch, 1, "target", 1, "target"))
        assertTrue(policy.matches(policy.epoch, 1, "target", 1, "target"))
    }
    @Test fun neverRemapMissingOrChangedTargets() {
        val p = NativeTargetPolicy()
        assertFalse(p.matches(0, 1, "a", 2, "a"))
        assertFalse(p.matches(0, 1, "a", 1, "b"))
        assertFalse(p.matches(0, 1, "a", null, null))
        assertFalse(p.matches(0, 1, "", 1, ""))
    }
    @Test fun preservesEscapedUrlComponents() {
        val p = BrowserDomainPolicy(setOf("https://example.com"))
        val url = "https://example.com/a%2Fb?q=a%20b%26c#section%20one"
        assertEquals(url, (p.evaluate(url) as NavigationDecision.Allow).normalizedUrl)
        assertTrue(p.evaluate("https://example.com%40evil.com/") is NavigationDecision.Block)
        assertTrue(p.evaluate("https://example.com@evil.com/") is NavigationDecision.Block)
    }
    @Test fun prototypeDoesNotBypassSafety() {
        assertTrue(BrowserSafetyPolicy.evaluate("input_text", "password", BrowserSafetyMode.PROTOTYPE_OFF) is BrowserActionDecision.UserTakeover)
        assertTrue(BrowserSafetyPolicy.evaluate("click_element_by_index", "pay credit card", BrowserSafetyMode.PROTOTYPE_OFF) is BrowserActionDecision.Block)
        assertTrue(BrowserSafetyPolicy.evaluate("click_element_by_index", "ordinary control", BrowserSafetyMode.PROTOTYPE_OFF) is BrowserActionDecision.Confirm)
    }
}
