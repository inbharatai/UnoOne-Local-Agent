package com.unoone.agent

import com.unoone.agent.browser.PendingBrowserHandoff
import org.junit.Assert.*
import org.junit.Test

class PendingBrowserHandoffTest {
    private val a = "https://example.com/a"
    private val b = "https://other.example/b"

    @Test fun queuedStopReadyNeverExecutesEvenDuringCancellation() {
        val handoff = PendingBrowserHandoff()
        handoff.offer(a, "A", false)
        val token = handoff.navigationStarted(a)
        handoff.revoke() // VM must do this before controller cancellation.
        var executions = 0
        fun ready() { if (handoff.consume(token, a, a, true, false) != null) executions++ }
        ready() // Simulate a reentrant cancellation callback.
        ready() // Later page readiness.
        assertEquals(0, executions)
    }

    @Test fun activeARejectsBWithoutNavigationOrChangingA() {
        val handoff = PendingBrowserHandoff()
        var currentPage = a
        var currentGoal = "A"
        val accepted = handoff.offer(b, "B", true)
        if (accepted != null) { currentPage = b; currentGoal = accepted.task }
        assertNull(accepted)
        assertEquals(a, currentPage)
        assertEquals("A", currentGoal)
        assertNull(handoff.consume(handoff.navigationStarted(b), b, b, true, true))
    }

    @Test fun validIdleWaitsForControllerThenConsumesOnce() {
        val handoff = PendingBrowserHandoff()
        handoff.offer(a, "A", false)
        val token = handoff.navigationStarted(a)
        assertNull(handoff.consume(token, a, a, false, false))
        assertEquals("A", handoff.consume(token, a, a, true, false)?.task)
        assertNull(handoff.consume(token, a, a, true, false))
    }

    @Test fun staleGenerationWrongOriginAndWrongDestinationCannotConsume() {
        val handoff = PendingBrowserHandoff()
        handoff.offer(a, "old", false)
        val old = handoff.navigationStarted(a)
        handoff.offer(a, "new", false)
        assertNull(handoff.consume(old, a, a, true, false))
        val token = handoff.navigationStarted(a)
        assertNull(handoff.consume(token, b, b, true, false))
        assertNull(handoff.consume(token, a, b, true, false))
        assertNull(handoff.consume(token, a, "https://example.com/different", true, false))
        assertEquals("new", handoff.consume(token, a, a, true, false)?.task)
    }
}
