package com.unoone.agent.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for outcome-learned memory: signature normalization, token-overlap retrieval with
 * failure preference + recency, and rendering. The Room persistence and the LLM that consumes the
 * hint are device-time-only; these tests pin the control contract.
 */
class OutcomeMemoryPolicyTest {

    @Test
    fun signatureNormalizesPunctuationCaseAndStopwords() {
        assertEquals("open chrome", OutcomeMemoryPolicy.signature("Open Chrome please!"))
        assertEquals("open chrome", OutcomeMemoryPolicy.signature("open, chrome."))
        assertEquals("delete notes meeting", OutcomeMemoryPolicy.signature("Delete the notes about my meeting"))
    }

    @Test
    fun signatureDropsStopwordsAndSingleChars() {
        assertEquals("buy milk", OutcomeMemoryPolicy.signature("i need to buy a milk"))
        // single-char tokens dropped, so "a b" → ""
        assertEquals("", OutcomeMemoryPolicy.signature("a b c"))
    }

    @Test
    fun relevantOutcomesMatchesOnSharedToken() {
        val outcomes = listOf(
            OutcomeRecord("open chrome", "open_chrome", success = true, updatedAt = 100),
            OutcomeRecord("send whatsapp mom", "send_whatsapp", success = false, updatedAt = 200)
        )
        val rel = OutcomeMemoryPolicy.relevantOutcomes("open chrome", outcomes)
        assertEquals(1, rel.size)
        assertEquals("open_chrome", rel.first().tool)
    }

    @Test
    fun failuresRankAboveSuccessesAtEqualOverlap() {
        val outcomes = listOf(
            OutcomeRecord("open chrome", "open_chrome", success = true, updatedAt = 300),
            OutcomeRecord("open chrome", "open_app", success = false, errorMessage = "app not found", updatedAt = 200)
        )
        val rel = OutcomeMemoryPolicy.relevantOutcomes("open chrome", outcomes)
        assertEquals("failure must surface first", false, rel.first().success)
        assertEquals("open_app", rel.first().tool)
    }

    @Test
    fun higherOverlapBeatsFailurePreference() {
        val outcomes = listOf(
            // 2-token overlap, success
            OutcomeRecord("delete notes meeting", "delete_notes", success = true, updatedAt = 50),
            // 1-token overlap, failure
            OutcomeRecord("open meeting", "open_app", success = false, updatedAt = 900)
        )
        val rel = OutcomeMemoryPolicy.relevantOutcomes("delete notes meeting", outcomes)
        assertEquals("higher overlap wins", "delete_notes", rel.first().tool)
    }

    @Test
    fun recencyBreaksTies() {
        val outcomes = listOf(
            OutcomeRecord("open chrome", "open_chrome", success = true, updatedAt = 10),
            OutcomeRecord("open chrome", "open_app", success = true, updatedAt = 90)
        )
        val rel = OutcomeMemoryPolicy.relevantOutcomes("open chrome", outcomes)
        assertEquals(90, rel.first().updatedAt)
    }

    @Test
    fun capsAtMaxN() {
        val outcomes = (1..5).map { OutcomeRecord("open chrome", "open_chrome", success = true, updatedAt = it.toLong()) }
        assertEquals(3, OutcomeMemoryPolicy.relevantOutcomes("open chrome", outcomes, maxN = 3).size)
    }

    @Test
    fun emptyCommandReturnsNoOutcomes() {
        val outcomes = listOf(OutcomeRecord("open chrome", "open_chrome", true))
        assertTrue(OutcomeMemoryPolicy.relevantOutcomes("", outcomes).isEmpty())
        assertTrue(OutcomeMemoryPolicy.relevantOutcomes("a b c", outcomes).isEmpty())
    }

    @Test
    fun renderFramesFailuresAsAvoidAndSuccessesAsWorked() {
        val rendered = OutcomeMemoryPolicy.render(listOf(
            OutcomeRecord("open chrome", "open_app", success = false, errorMessage = "app not installed"),
            OutcomeRecord("open chrome", "open_chrome", success = true)
        ))
        assertTrue(rendered.contains("prior avoid: open_app (app not installed)"))
        assertTrue(rendered.contains("prior worked: open_chrome"))
    }

    @Test
    fun renderEmptyIsBlank() {
        assertEquals("", OutcomeMemoryPolicy.render(emptyList()))
    }
}