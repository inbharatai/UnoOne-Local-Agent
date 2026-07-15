package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseTextJoinerTest {

    @Test
    fun singleFragmentReturnedTrimmed() {
        assertEquals("hello", ResponseTextJoiner.join(listOf("hello")))
    }

    @Test
    fun multipleFragmentsAreConcatenatedInOrderNotJustFirst() {
        // Regression: the old firstNotNullOfOrNull kept only the first fragment, so a multi-part
        // answer collapsed to its first fragment. Join must concatenate all parts in order.
        val result = ResponseTextJoiner.join(listOf("God is ", "a word ", "people use ", "for meaning."))
        assertEquals("God is a word people use for meaning.", result)
    }

    @Test
    fun nullsInTheMiddleAreSkipped() {
        assertEquals("abc", ResponseTextJoiner.join(listOf("a", null, "b", null, "c")))
    }

    @Test
    fun blankFragmentsProduceNonNullWhenAnyNonBlank() {
        assertEquals("hi", ResponseTextJoiner.join(listOf("", "  ", "hi", "")))
    }

    @Test
    fun allBlankReturnsNull() {
        assertNull(ResponseTextJoiner.join(listOf("", "   ", "\t", "\n")))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(ResponseTextJoiner.join(emptyList()))
    }

    @Test
    fun allNullsReturnsNull() {
        assertNull(ResponseTextJoiner.join(listOf(null, null, null)))
    }

    @Test
    fun resultIsTrimmed() {
        assertEquals("hi there", ResponseTextJoiner.join(listOf("  hi there  ")))
    }

    @Test
    fun hindiMultiFragmentAnswerIsFullyPreserved() {
        // Eyes-free / language-control regression: a multi-fragment Hindi answer must not truncate.
        val result = ResponseTextJoiner.join(listOf("ईश्वर ", "एक शब्द ", "है जिसका ", "अर्थ होता है।"))
        assertEquals("ईश्वर एक शब्द है जिसका अर्थ होता है।", result)
    }
}