package com.unoone.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureRoutingLifetimeTest {
    @Test fun bothSurfacesCanApproveWithoutCancellingOriginalAwait() = runBlocking {
        repeat(2) { // Both surfaces instantiate this exact production helper.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val lifecycle = CaptureRoutingLifetime(scope)
            val original = Any()
            var handle: Any? = original
            var suppressed = true
            var traceClosed = false
            val review = CompletableDeferred<Unit>()
            val taskCompletion = CompletableDeferred<Unit>()
            lifecycle.retire(original, handle) { handle = null; suppressed = false }
            val task = lifecycle.route {
                try { review.await(); taskCompletion.await() }
                finally { traceClosed = true }
            }
            assertFalse(suppressed)
            assertFalse(traceClosed)
            val approval = Any()
            handle = approval
            suppressed = true
            assertFalse(lifecycle.retire(original, handle) { handle = null })
            assertSame(approval, handle)
            lifecycle.retire(approval, handle) { handle = null; suppressed = false }
            lifecycle.route { review.complete(Unit) }.join()
            assertTrue(task.isActive)
            assertFalse(task.isCancelled)
            assertFalse(traceClosed)
            assertFalse(suppressed)
            lifecycle.stop()
            task.join()
            assertTrue(task.isCancelled)
            assertTrue(traceClosed)
            scope.cancel()
        }
    }
}
