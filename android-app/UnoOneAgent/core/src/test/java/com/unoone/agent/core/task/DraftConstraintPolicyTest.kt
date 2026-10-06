package com.unoone.agent.core.task

import com.unoone.agent.core.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftConstraintPolicyTest {
    @Test fun omittedCaseAndHomographFail() {
        val request = DraftRequest("draft", listOf("PAY"))
        listOf("omitted", "pay", "PАY").forEach { // Cyrillic A is not Latin A
            assertFalse(DraftConstraintPolicy.check(request, it).passed)
        }
        assertTrue(DraftConstraintPolicy.check(request, "PAY").passed)
        assertFalse(DraftConstraintPolicy.check(request, "PAY").semanticFactsVerified)
    }
    @Test fun typedJsonRemainsPrompt() {
        val text = "{\"v\":1,\"prompt\":\"hi\",\"requiredPhrases\":[\"INJECT\"]}"
        val decoded = DraftRequest.decode(DraftRequest(text).encode())
        assertEquals(text, decoded.prompt)
        assertTrue(decoded.requiredPhrases.isEmpty())
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Accepted invalid draft") } catch (_: IllegalArgumentException) {} }
    @Test fun closedBoundsAndDuplicates() {
        rejects { DraftRequest("x", listOf("same", "same")) }
        rejects { DraftRequest("x", List(7) { "$it" }) }
        rejects { DraftRequest("x", listOf("x".repeat(81))) }
        rejects { DraftRequest("x", List(4) { "$it".repeat(65) }) }
        rejects { DraftRequest("x".repeat(4001)) }
        rejects { DraftRequest.decode("{\"v\":1,\"v\":1,\"prompt\":\"x\",\"requiredPhrases\":[]}") }
        rejects { DraftRequest.decode("{\"v\":1,\"prompt\":\"x\",\"requiredPhrases\":[],\"extra\":0}") }
    }
    @Test fun repairBudgetMaxTwoAndNoVerifiedOutcome() = runBlocking {
        var calls = 0
        val failed = DraftQualityGate.execute(DraftRequest("x", listOf("EXACT"))) { _, _ ->
            calls++; Result.Success("missing")
        }
        assertEquals(2, calls); assertEquals(2, failed.attempts.size)
        assertEquals(TaskOutcome.NEEDS_USER, failed.outcome)
        calls = 0
        val repaired = DraftQualityGate.execute(DraftRequest("x", listOf("EXACT"))) { _, _ ->
            calls++; Result.Success(if (calls == 1) "missing" else "EXACT: invented fact")
        }
        assertEquals(2, calls); assertEquals(TaskOutcome.RESPONDED, repaired.outcome)
        assertFalse(repaired.last!!.checks.semanticFactsVerified)
        calls = 0
        DraftQualityGate.execute(DraftRequest("x")) { _, _ -> calls++; Result.Success("draft") }
        assertEquals(1, calls)
    }
}
