package com.unoone.agent.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBrowserTaskRoutingTest {

    @Test
    fun `plain read tasks use deterministic rendered page reader`() {
        assertTrue(isReadOnlyPageTask("read the page"))
        assertTrue(isReadOnlyPageTask("Read this page aloud"))
        assertTrue(isReadOnlyPageTask("what's on this page?"))
        assertTrue(isReadOnlyPageTask("describe this page"))
    }

    @Test
    fun `read plus interaction stays with Page Agent`() {
        assertFalse(isReadOnlyPageTask("read the page and fill the contact form"))
        assertFalse(isReadOnlyPageTask("read this page, click Apply, and stop"))
        assertFalse(isReadOnlyPageTask("fill the form"))
    }
}
