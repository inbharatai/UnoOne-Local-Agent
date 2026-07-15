package com.unoone.agent.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PlainTextExtractorTest {

    @Test
    fun readsUtf8StreamVerbatim() {
        val src = "Hello, UnoOne — revenue £42, café naïve\nline two"
        val text = PlainTextExtractor.extract(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals(src, text)
    }

    @Test
    fun capsToMaxChars() {
        val src = "0123456789".repeat(1000)
        val text = PlainTextExtractor.extract(ByteArrayInputStream(src.toByteArray()), maxChars = 250)
        assertEquals(250, text.length)
        assertTrue(text.startsWith("0123456789"))
    }

    @Test
    fun capHelperShorterThanLimitIsUnchanged() {
        assertEquals("short", PlainTextExtractor.cap("short", 100))
    }
}