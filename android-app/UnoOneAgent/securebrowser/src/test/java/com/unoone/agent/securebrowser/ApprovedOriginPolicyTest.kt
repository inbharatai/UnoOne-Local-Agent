package com.unoone.agent.securebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for [ApprovedOriginPolicy] — the `secure_browser_task` origin gate. Pure logic; the
 * live WebView navigation + PageAgent execution are device-time gates, not JVM-assertable.
 */
class ApprovedOriginPolicyTest {

    @Test
    fun approvedOriginsListIsRealAndNonEmpty() {
        // Honesty guard: no placeholder/fake origins. Every entry is https + a real host.
        assertTrue(ApprovedOriginPolicy.APPROVED_ORIGINS.isNotEmpty())
        for (origin in ApprovedOriginPolicy.APPROVED_ORIGINS) {
            assertTrue("origin must be https: $origin", origin.startsWith("https://"))
            assertTrue("origin must not end with /: $origin", !origin.endsWith("/"))
        }
    }

    @Test
    fun resolveAcceptsFullApprovedUrl() {
        assertEquals(
            "https://unigurus.com",
            ApprovedOriginPolicy.resolve("https://unigurus.com/apply?step=1")
        )
        assertEquals("https://inbharat.ai", ApprovedOriginPolicy.resolve("https://inbharat.ai/"))
    }

    @Test
    fun resolveRejectsUnapprovedOrigin() {
        assertNull(ApprovedOriginPolicy.resolve("https://evil.example.com/"))
        assertNull(ApprovedOriginPolicy.resolve("https://shop.inbharat.ai/"))
    }

    @Test
    fun resolveRejectsUnsafeSchemes() {
        assertNull(ApprovedOriginPolicy.resolve("http://unigurus.com/"))
        assertNull(ApprovedOriginPolicy.resolve("javascript:alert(1)"))
        assertNull(ApprovedOriginPolicy.resolve("https://127.0.0.1/"))
        assertNull(ApprovedOriginPolicy.resolve("https://user:pass@unigurus.com/"))
    }

    @Test
    fun originForResolvesFriendlyNames() {
        assertEquals("https://unigurus.com", ApprovedOriginPolicy.originFor("unigurus"))
        assertEquals("https://unigurus.com", ApprovedOriginPolicy.originFor("UniGurus"))
        assertEquals("https://unigurus.com", ApprovedOriginPolicy.originFor("uni guru"))
        assertEquals("https://uniassist.ai", ApprovedOriginPolicy.originFor("uniassist"))
        assertEquals("https://uniassist.ai", ApprovedOriginPolicy.originFor("uni-assist"))
        assertEquals("https://testsprep.in", ApprovedOriginPolicy.originFor("testsprep"))
        assertEquals("https://inbharat.ai", ApprovedOriginPolicy.originFor("inbharat"))
        assertEquals("https://inbharat.ai", ApprovedOriginPolicy.originFor("in bharat"))
    }

    @Test
    fun originForResolvesBareHostsAndFullUrls() {
        assertEquals("https://unigurus.com", ApprovedOriginPolicy.originFor("unigurus.com"))
        assertEquals("https://www.unigurus.com", ApprovedOriginPolicy.originFor("www.unigurus.com"))
        assertEquals(
            "https://uniassist.ai",
            ApprovedOriginPolicy.originFor("https://uniassist.ai/some/path")
        )
    }

    @Test
    fun originForRejectsUnknownAndBlank() {
        assertNull(ApprovedOriginPolicy.originFor(""))
        assertNull(ApprovedOriginPolicy.originFor("   "))
        assertNull(ApprovedOriginPolicy.originFor("google"))
        assertNull(ApprovedOriginPolicy.originFor("https://google.com/"))
    }

    @Test
    fun isApprovedMatchesOriginFor() {
        assertTrue(ApprovedOriginPolicy.isApproved("unigurus"))
        assertTrue(!ApprovedOriginPolicy.isApproved("google"))
        assertTrue(!ApprovedOriginPolicy.isApproved(""))
    }
}