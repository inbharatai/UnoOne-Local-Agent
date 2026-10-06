package com.unoone.agent.task

import com.unoone.agent.core.task.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Fake independent native lanes; no Android/native engine involved. */
class CrossLaneResourceTest {
    @Test fun independentUiLanesNeverOverlap() = runBlocking {
        val active = AtomicInteger(); val max = AtomicInteger()
        coroutineScope {
            repeat(2) { lane -> launch(Dispatchers.Default) {
                repeat(12) { ProcessTaskResources.ui.withLease(TaskId("lane-$lane"), {}) {
                    val n = active.incrementAndGet(); max.updateAndGet { old -> maxOf(old, n) }
                    delay(1); active.decrementAndGet()
                } }
            } }
        }
        assertEquals(1, max.get())
    }
    @Test fun inheritedChildCannotReacquireUi() = runBlocking {
        ProcessTaskResources.ui.withLease(TaskId("outer"), {}) {
            val rejected = coroutineScope { async { runCatching { ProcessTaskResources.ui.withLease(TaskId("outer"), {}) {} }.isFailure }.await() }
            assertTrue(rejected)
        }
    }
    @Test fun synchronousRevocationBlocksLateEffectRetainsOwnerUntilExit() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val exit = CompletableDeferred<Unit>()
        val owner = TaskId("old"); var revoked = false; var effects = 0
        val job = launch {
            ProcessTaskResources.ui.withLease(owner, { check(!revoked) }) { lease ->
                entered.complete(Unit)
                withContext(NonCancellable) {
                    exit.await()
                    runCatching { lease.checkActive(); effects++ }
                }
            }
        }
        entered.await(); revoked = true; ProcessTaskResources.ui.cancelOwner(owner)
        assertEquals(owner, ProcessTaskResources.ui.owner())
        exit.complete(Unit); job.join()
        assertEquals(0, effects); assertNull(ProcessTaskResources.ui.owner())
    }
    @Test fun cancellingDifferentOwnerNeverCancelsModel() = runBlocking {
        ProcessTaskResources.model.withLease(TaskId("model-A"), {}) { lease ->
            assertFalse(ProcessTaskResources.model.cancelOwner(TaskId("model-B")))
            lease.checkActive(); currentCoroutineContext().ensureActive()
            assertEquals(TaskId("model-A"), ProcessTaskResources.model.owner())
        }
    }
}
