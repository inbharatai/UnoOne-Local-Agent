package com.unoone.agent.voice

import com.unoone.agent.core.model.Result
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureOwnershipTest {
    @Test fun busyOwnerCannotStartOrStopSibling() {
        val gate = CaptureOwnership()
        val first = Any(); val sibling = Any()
        var starts = 0; var stops = 0
        assertTrue(gate.acquire(first) { starts++; Result.Success(Unit) } is Result.Success)
        assertTrue(gate.acquire(first) { starts++; Result.Success(Unit) } is Result.Success)
        assertTrue(gate.acquire(sibling) { starts++; Result.Success(Unit) } is Result.Error)
        gate.matching(sibling) { stops++ }
        assertEquals(1, starts); assertEquals(0, stops)
        gate.stopAll { stops++ }
        gate.acquire(sibling) { starts++; Result.Success(Unit) }
        gate.matching(first) { stops++ }
        gate.release(first)
        assertTrue(gate.acquire(first) { Result.Success(Unit) } is Result.Error)
        assertEquals(1, stops)
    }

    @Test fun thrownNativeStartRollsBackBeforeNextOwner() {
        val gate = CaptureOwnership()
        var live = false
        try {
            gate.acquire(Any(), { live = false }) { live = true; error("native start") }
            fail("must throw")
        } catch (_: IllegalStateException) { }
        assertFalse(live)
        assertTrue(gate.acquire(Any()) { Result.Success(Unit) } is Result.Success)
    }

    @Test fun cancellingCaptureDrainsChildAndReleasesFakeNative() = runBlocking {
        val gate = CaptureOwnership()
        val token = Any()
        val started = CompletableDeferred<Unit>()
        var live = false; var drained = false
        val task = launch {
            coroutineScope {
                var stt: Job? = null
                try {
                    gate.acquire(token) { live = true; Result.Success(Unit) }
                    stt = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() }
                        finally { withContext(NonCancellable) { delay(10); drained = true } }
                    }
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        gate.matching(token) { live = false }
                        try { stt?.cancelAndJoin() } finally { gate.release(token) }
                    }
                }
            }
        }
        started.await()
        task.cancelAndJoin()
        assertFalse(live); assertTrue(drained)
        assertTrue(gate.acquire(Any()) { Result.Success(Unit) } is Result.Success)
    }
}
