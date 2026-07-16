package com.unoone.agent.securebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAgentTaskResultTest {
    @Test
    fun decodesStructuredSuccessAndFailureWithoutWordGuessing() {
        val success = PageAgentTaskResultDecoder.decode(
            """{"success":true,"data":"No Error here","taskId":7}"""
        ).getOrThrow()
        assertTrue(success.success)
        assertEquals("No Error here", success.data)
        assertEquals(7L, success.taskId)

        val failure = PageAgentTaskResultDecoder.decode("""{"success":false,"data":"Stopped safely"}""").getOrThrow()
        assertFalse(failure.success)
    }

    @Test
    fun decodesQuotedWebViewJsonAndRejectsGarbage() {
        val quoted = "\"{\\\"success\\\":true,\\\"data\\\":\\\"done\\\"}\""
        assertEquals("done", PageAgentTaskResultDecoder.decode(quoted).getOrThrow().data)
        assertTrue(PageAgentTaskResultDecoder.decode("not-json").isFailure)
    }

    @Test
    fun gateRejectsConcurrencyAndStaleCompletion() {
        val gate = PageAgentTaskGate()
        val first = gate.begin()
        assertNotNull(first)
        assertEquals(first, gate.activeId())
        assertNull(gate.begin())
        assertTrue(gate.tryComplete(first!!))
        assertNull(gate.activeId())
        assertFalse(gate.tryComplete(first))
        val second = gate.begin()
        assertEquals(second, gate.cancelActive())
        assertFalse(gate.tryComplete(second!!))
    }
}
