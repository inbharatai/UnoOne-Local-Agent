package com.unoone.agent.core.document

/**
 * C8: real (dependency-free) HTML → plain-text extraction. Drops `<script>`/`<style>` blocks,
 * turns block-level elements into newlines so headings/paragraphs/rows stay readable, strips the
 * remaining tags, and unescapes the common HTML entities — including numeric `&#NN;`. No Jsoup dep
 * needed; this is the actual text a user would read, capped to fit the brain's context window.
 */
object HtmlTextExtractor {

    private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
    private val BLOCK_TAG = Regex("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|td|th|table|section|article|header|footer)[^>]*>")
    private val ANY_TAG = Regex("<[^>]+>")
    private val WS_RUN = Regex("[ \\t]+")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#x([0-9A-Fa-f]+);")

    fun extract(html: String, maxChars: Int = PlainTextExtractor.DEFAULT_MAX_CHARS): String {
        var s = html
        s = SCRIPT_OR_STYLE.replace(s, " ")
        s = BLOCK_TAG.replace(s) { "\n" }
        s = ANY_TAG.replace(s, "")
        s = unescapeEntities(s)
        // Collapse runs of spaces/tabs, trim each line, drop blank lines.
        s = s.replace(WS_RUN, " ")
        return PlainTextExtractor.cap(
            s.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"),
            maxChars
        )
    }

    /** Unescapes the common named + numeric HTML entities. */
    fun unescapeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&copy;", "(c)")
        .replace("&reg;", "(r)")
        .let { NUMERIC_ENTITY.replace(it) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value } }
        .let { HEX_ENTITY.replace(it) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value } }
}