package com.unoone.agent.core.runtime

import org.junit.Assert.*
import org.junit.Test

class GlobalTaskCancellationTest {
    @Test fun fansOutDespiteFailureAndDoesNotRecurse() {
        val owner = Any()
        var calls = 0
        val first = GlobalTaskCancellation.register(owner) { GlobalTaskCancellation.cancelAll(); error("failed owner") }
        val second = GlobalTaskCancellation.register(owner) { calls++ }
        val generation = GlobalTaskCancellation.generation
        try {
            GlobalTaskCancellation.cancelAll()
            assertEquals(1, calls)
            assertEquals(generation + 1, GlobalTaskCancellation.generation)
        } finally { first.close(); second.close() }
    }

    @Test fun unregisterDisablesHandlerAlreadyInDispatchSnapshot() {
        val owner = Any()
        var calls = 0
        lateinit var later: AutoCloseable
        val first = GlobalTaskCancellation.register(owner) { later.close() }
        later = GlobalTaskCancellation.register(owner) { calls++ }
        try { GlobalTaskCancellation.cancelAll(); assertEquals(0, calls) }
        finally { first.close(); later.close() }
    }

    @Test fun workerStopFinishesRevocationBeforeReturning() {
        val owner = Any()
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val registration = GlobalTaskCancellation.register(owner) { active.set(false) }
        try {
            val worker = Thread { GlobalTaskCancellation.cancelAll(); assertFalse(active.get()) }
            worker.start(); worker.join(2000)
            assertFalse(worker.isAlive)
            assertFalse(active.get())
        } finally { registration.close() }
    }
}
