package com.unoone.agent.core.model

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class E4bRuntimeCoordinatorTest {
    @Test fun `phone browser load inference and close operations are process serialized`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        List(12) {
            async {
                E4bRuntimeCoordinator.operationMutex.withLock {
                    val now = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    delay(5)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, peak.get())
    }

    @Test fun `state snapshot never contains private prompt text by design`() {
        E4bRuntimeCoordinator.transition(E4bRuntimeState.PHONE_READY, "phone", "CPU")
        assertEquals(E4bRuntimeState.PHONE_READY, E4bRuntimeCoordinator.snapshot().state)
        assertEquals("CPU", E4bRuntimeCoordinator.snapshot().detail)
    }
}
