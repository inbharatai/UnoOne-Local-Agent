package com.unoone.agent

import com.unoone.agent.browser.BrowserAsyncOwnership
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class BrowserAsyncOwnershipTest {
    @Test fun transcriptStopLateNativeSuccessCannotSubmit() = runBlocking {
        val gate = BrowserAsyncOwnership { 0L }
        val capture = gate.capture()
        val result = CompletableDeferred<String>()
        val actions = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                val text = result.await()
                gate.runIfCurrent(capture) { actions += text }
            }
        }
        gate.revoke { job.cancel() }
        result.complete("fill form")
        job.join()
        assertTrue(actions.isEmpty())
    }

    @Test fun pageReadStopLateTextCannotNarrate() = runBlocking {
        val gate = BrowserAsyncOwnership { 0L }
        val ticket = gate.capture()
        val callback = CompletableDeferred<String>()
        val spoken = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val text = callback.await()
            gate.runIfCurrent(ticket) { spoken += text }
        }
        gate.revoke()
        callback.complete("private page")
        job.join()
        assertTrue(spoken.isEmpty())
    }

    @Test fun localCancellationAllowsDistinctNewRequestWithoutGlobalChange() = runBlocking {
        val gate = BrowserAsyncOwnership { 7L }
        val old = gate.capture()
        val late = CompletableDeferred<Unit>()
        var actions = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            late.await()
            gate.runIfCurrent(old) { actions++ }
        }
        gate.revoke()
        val fresh = gate.capture()
        assertNotEquals(old, fresh)
        gate.runIfCurrent(fresh) { actions++ }
        late.complete(Unit)
        job.join()
        assertEquals(1, actions)
    }

    @Test fun clearIsTerminalForDelayedAndNewOperations() = runBlocking {
        val gate = BrowserAsyncOwnership { 0L }
        val ticket = gate.capture()
        val late = CompletableDeferred<Unit>()
        var actions = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            late.await()
            gate.runIfCurrent(ticket) { actions++ }
        }
        gate.revoke(close = true)
        late.complete(Unit)
        job.join()
        gate.runIfCurrent(gate.capture()) { actions++ }
        assertEquals(0, actions)
    }

    @Test fun globalGenerationRevokesBeforeLocalCallback() {
        var generation = 1L
        val gate = BrowserAsyncOwnership { generation }
        val ticket = gate.capture()
        generation++
        assertFalse(gate.isCurrent(ticket))
    }
}
