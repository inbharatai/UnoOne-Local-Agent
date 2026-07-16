package com.unoone.agent.securebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextResultDecoderTest {

    @Test
    fun `unwraps WebView double encoded page text`() {
        val raw = "\"{\\\"title\\\":\\\"Application form\\\",\\\"text\\\":\\\"First name Country Submit\\\"}\""
        assertEquals(
            "Application form. First name Country Submit",
            PageTextResultDecoder.decode(raw)
        )
    }

    @Test
    fun `bounds very long spoken page text`() {
        val raw = "{\"title\":\"\",\"text\":\"${"x".repeat(5_000)}\"}"
        assertTrue(PageTextResultDecoder.decode(raw).length == 4_000)
    }
}
