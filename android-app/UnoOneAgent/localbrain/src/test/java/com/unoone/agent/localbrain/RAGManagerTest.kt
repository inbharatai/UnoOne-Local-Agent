package com.unoone.agent.localbrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM parser tests for [RAGManager.parseDuckDuckGoHtml] — no network, no device.
 * Verifies attributed results (title, URL, snippet, domain) and graceful degradation when
 * DDG markup changes or is empty.
 */
class RAGManagerTest {

    private val sampleHtml = """
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title"><a class="result__a" rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=abc">Example Page Title</a></h2>
          <a class="result__snippet" rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage">This is the <b>snippet</b> text &amp; more</a>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fnews.org%2Fstory">News Org Story</a></h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fnews.org%2Fstory">A second result snippet</a>
        </div>
    """.trimIndent()

    @Test
    fun parsesAttributedResultsWithTitleUrlSnippetDomain() {
        val results = RAGManager.parseDuckDuckGoHtml(sampleHtml, maxResults = 3)
        assertEquals(2, results.size)

        val first = results[0]
        assertEquals("Example Page Title", first.title)
        assertEquals("https://example.com/page", first.url)
        assertEquals("example.com", first.domain)
        assertTrue(first.snippet.contains("snippet text"))
        // HTML entities + tags are cleaned in the snippet.
        assertTrue(first.snippet.contains("& more"))
        assertTrue(!first.snippet.contains("<b>"))
    }

    @Test
    fun decodesUddgRedirectToRealUrl() {
        val results = RAGManager.parseDuckDuckGoHtml(sampleHtml, maxResults = 3)
        assertEquals("https://news.org/story", results[1].url)
        assertEquals("news.org", results[1].domain)
    }

    @Test
    fun respectsMaxResultsLimit() {
        val results = RAGManager.parseDuckDuckGoHtml(sampleHtml, maxResults = 1)
        assertEquals(1, results.size)
    }

    @Test
    fun returnsEmptyOnUnrecognizedMarkup() {
        // If DDG changes their markup, the parser must return nothing (caller surfaces "no results"),
        // never throw or return garbage.
        val results = RAGManager.parseDuckDuckGoHtml("<html><body>totally unrelated markup</body></html>", 3)
        assertTrue(results.isEmpty())
    }

    @Test
    fun formatResultsIsAttributedAndIndexed() {
        val results = RAGManager.parseDuckDuckGoHtml(sampleHtml, 3)
        val formatted = RAGManager.formatResults("test", results)
        assertTrue(formatted.contains("[1] Example Page Title"))
        assertTrue(formatted.contains("URL: https://example.com/page"))
        assertTrue(formatted.contains("example.com"))
    }
}