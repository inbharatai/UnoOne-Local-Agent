package com.unoone.agent.securebrowser

import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomainPolicyTest {

    private val policy = BrowserDomainPolicy(
        setOf(
            "https://unigurus.com",
            "https://uniassist.ai",
            "https://testsprep.in"
        )
    )

    @Test
    fun `allows exact approved https origin`() {
        assertTrue(policy.evaluate("https://unigurus.com/apply?step=1") is NavigationDecision.Allow)
    }

    @Test
    fun `does not implicitly trust subdomains`() {
        assertTrue(policy.evaluate("https://evil.unigurus.com/") is NavigationDecision.Block)
    }

    @Test
    fun `blocks cleartext local and ip urls`() {
        assertTrue(policy.evaluate("http://unigurus.com/") is NavigationDecision.Block)
        assertTrue(policy.evaluate("https://localhost/") is NavigationDecision.Block)
        assertTrue(policy.evaluate("https://127.0.0.1/") is NavigationDecision.Block)
        assertTrue(policy.evaluate("javascript:alert(1)") is NavigationDecision.Block)
    }

    @Test
    fun `blocks urls containing user info`() {
        assertTrue(policy.evaluate("https://user:pass@unigurus.com/") is NavigationDecision.Block)
    }
}
