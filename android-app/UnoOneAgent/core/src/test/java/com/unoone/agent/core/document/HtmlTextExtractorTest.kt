package com.unoone.agent.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextExtractorTest {

    @Test
    fun stripsTagsAndKeepsBlockStructureAsLines() {
        val html = """
            <html><head><title>Ignored Title</title></head>
            <body>
              <h1>UnoOne Report</h1>
              <p>Total revenue is &pound; not real, but &amp; is an ampersand.</p>
              <table><tr><td>Name</td><td>Value</td></tr></table>
            </body></html>
        """.trimIndent()

        val text = HtmlTextExtractor.extract(html)

        // Tags are gone. (<title> survives as a stripped line because it is not a block element we
        // convert — that's acceptable; assert the meaningful body content survives.)
        assertTrue("heading survives", text.contains("UnoOne Report"))
        assertTrue("paragraph survives", text.contains("Total revenue is"))
        assertTrue("ampersand unescaped", text.contains("but & is an ampersand."))
        assertTrue("table cells survive", text.contains("Name") && text.contains("Value"))
        assertFalse("no raw tags remain", text.contains("<") || text.contains(">"))
        // Block elements produced newlines, so heading and paragraph are on separate lines.
        assertTrue("block structure preserved", text.contains("UnoOne Report\nTotal revenue is"))
    }

    @Test
    fun dropsScriptAndStyleBlocks() {
        val html = """
            <html><body>
            <style>body { color: red; }</style>
            <script>alert("x"); var x = 1 < 2;</script>
            <p>Visible only.</p>
            </body></html>
        """.trimIndent()

        val text = HtmlTextExtractor.extract(html)
        assertTrue(text.contains("Visible only."))
        assertFalse("script body removed", text.contains("alert"))
        assertFalse("style body removed", text.contains("color: red"))
        // The JS comparison operators that would survive as stray text are gone with the script block.
        assertFalse("no script artefacts", text.contains("var x"))
    }

    @Test
    fun unescapesNumericEntities() {
        val html = "<p>&#65;&#66;&#67; and &#x44;&#x45;&#x46;</p>"
        val text = HtmlTextExtractor.extract(html)
        assertTrue("decimal entities -> ABC", text.contains("ABC"))
        assertTrue("hex entities -> DEF", text.contains("DEF"))
    }

    @Test
    fun capsLargeHtmlToMaxChars() {
        val html = "<p>" + "a".repeat(10_000) + "</p>"
        val text = HtmlTextExtractor.extract(html, maxChars = 500)
        assertEquals(500, text.length)
    }
}