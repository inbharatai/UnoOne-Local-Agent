package com.unoone.agent.core.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class E4bRuntimeGateTest {
    private class NativeHeap {
        var resident = 0
        var peak = 0
        fun allocate() { resident++; peak = maxOf(peak, resident) }
    }
    private class FakeEngine(val gate: E4bRuntimeGate, val heap: NativeHeap, val owner: String) {
        var loaded = false
        var closes = 0
        suspend fun load(token: String) = gate.operationMutex.withLock {
            if (!gate.canReplaceAllocation(this, token)) return@withLock false
            if (loaded) closeLocked()
            if (!gate.claimAllocation(this, token, owner)) return@withLock false
            heap.allocate()
            loaded = true
            true
        }
        fun closeLocked() {
            check(loaded)
            closes++
            heap.resident--
            loaded = false
            gate.acknowledgeClosed(this)
        }
        suspend fun close() = gate.operationMutex.withLock { closeLocked() }
    }

    @Test fun `in flight native operation blocks reserve then browser waits for phone close`() = runBlocking {
        val gate = E4bRuntimeGate()
        val heap = NativeHeap()
        val phone = FakeEngine(gate, heap, E4bRuntimeCoordinator.PHONE_OWNER)
        val browser = FakeEngine(gate, heap, "browser")
        assertTrue(phone.load(E4bRuntimeCoordinator.PHONE_OWNER))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val inference = async(start = CoroutineStart.UNDISPATCHED) {
            gate.operationMutex.withLock { entered.complete(Unit); finish.await() }
        }
        entered.await()
        val reservation = async(start = CoroutineStart.UNDISPATCHED) { gate.reserve("browser") }
        assertFalse(reservation.isCompleted)
        finish.complete(Unit)
        inference.await()
        assertTrue(reservation.await())
        assertFalse(browser.load("browser"))
        assertEquals(0, phone.closes)
        assertFalse(phone.load(E4bRuntimeCoordinator.PHONE_OWNER))
        assertEquals(0, phone.closes)
        phone.close()
        assertTrue(browser.load("browser"))
        assertFalse(gate.releaseReservation("browser"))
        browser.close()
        assertTrue(phone.load("browser")) // Restoration remains under browser reservation.
        assertTrue(gate.releaseReservation("browser"))
        assertEquals(1, heap.peak)
        phone.close()
        assertEquals(0, heap.resident)
    }

    @Test fun `close barrier cannot be overtaken by another native allocation`() = runBlocking {
        val gate = E4bRuntimeGate()
        val heap = NativeHeap()
        val phone = FakeEngine(gate, heap, E4bRuntimeCoordinator.PHONE_OWNER)
        val browser = FakeEngine(gate, heap, "browser")
        assertTrue(phone.load(E4bRuntimeCoordinator.PHONE_OWNER))
        assertTrue(gate.reserve("browser"))
        val entered = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val closing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.operationMutex.withLock { entered.complete(Unit); allowClose.await(); phone.closeLocked() }
        }
        entered.await()
        val loading = async(start = CoroutineStart.UNDISPATCHED) { browser.load("browser") }
        assertFalse(loading.isCompleted)
        assertEquals(1, heap.resident)
        allowClose.complete(Unit)
        closing.await()
        assertTrue(loading.await())
        assertEquals(1, heap.peak)
        browser.close()
        assertTrue(gate.releaseReservation("browser"))
    }

    @Test fun `uncertain close stays quarantined despite acknowledgment and wrong holder`() = runBlocking {
        val gate = E4bRuntimeGate()
        val heap = NativeHeap()
        val browser = FakeEngine(gate, heap, "browser")
        assertTrue(gate.reserve("browser"))
        assertTrue(browser.load("browser"))
        gate.operationMutex.withLock {
            gate.quarantine(browser) // Simulated throwing native close: allocation still exists.
            gate.acknowledgeClosed(Any())
            gate.acknowledgeClosed(browser) // Mere assertion is not evidence after a throwing close.
        }
        assertFalse(gate.releaseReservation("browser"))
        assertFalse(browser.load("browser"))
        assertFalse(gate.reserve("other"))
        assertEquals(1, heap.resident)
        assertEquals(0, browser.closes)
        // No reset API: only a fresh process/gate may leave this sticky quarantine.
    }
}
