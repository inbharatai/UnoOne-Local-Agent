package com.unoone.agent.task

import com.unoone.agent.core.task.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransitionSchedulingTest {
    @Test fun sameCoroutineMayReuseButChildCannot() = runBlocking {
        val id = TaskId("transition")
        ProcessTaskResources.model.withLease(id, {}) { lease ->
            assertSame(lease, ProcessTaskResources.model.currentLease())
            supervisorScope {
                val child = async { runCatching { ProcessTaskResources.model.currentLease() }.isFailure }
                assertTrue(child.await())
            }
        }
        assertNull(ProcessTaskResources.model.owner())
    }

    @Test fun stopAtUnloadBarrierRejectsLateActivationAndSerializesNextMode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val ack = CompletableDeferred<Unit>()
        var generation = 0
        var activated = false
        var browserEntered = false
        val blind = launch {
            val captured = generation
            ProcessTaskResources.model.withLease(TaskId("blind"), { check(captured == generation) }) {
                entered.complete(Unit)
                ack.await() // fake native unload: still owns scheduler until acknowledgement
                if (captured == generation) activated = true
            }
        }
        entered.await()
        val browser = launch {
            ProcessTaskResources.model.withLease(TaskId("browser"), {}) { browserEntered = true }
        }
        yield()
        generation++
        assertFalse(browserEntered)
        assertEquals(TaskId("blind"), ProcessTaskResources.model.owner())
        ack.complete(Unit)
        blind.join(); browser.join()
        assertFalse(activated)
        assertTrue(browserEntered)
    }
}
