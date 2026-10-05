package com.unoone.agent.voice.stt

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeLifecycleGateTest {
    private class Native {
        val closes = AtomicInteger()
        val decoding = AtomicInteger()
        fun close() { check(decoding.get() == 0); check(closes.incrementAndGet() == 1) }
    }
    private fun CountDownLatch.awaitBarrier() = check(await(3, TimeUnit.SECONDS))

    @Test fun coldConstructorReturningAfterDestroyDisposesWithoutPublishing() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val candidate = Native()
        val gate = NativeLifecycleGate<Native> { it.close() }
        try {
            val init = pool.submit<Boolean> { gate.initialize { entered.countDown(); finish.awaitBarrier(); candidate } }
            entered.awaitBarrier()
            // This must return while the constructor remains blocked, just like onDestroy on Main.
            val invalidated = pool.submit { gate.invalidate() }
            invalidated.get(1, TimeUnit.SECONDS)
            assertFalse(gate.hasResource())
            finish.countDown()
            assertFalse(init.get(3, TimeUnit.SECONDS))
            gate.close(); gate.close()
            assertEquals(1, candidate.closes.get())
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun releaseWaitsForDecodeAcknowledgement() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val native = Native()
        val gate = NativeLifecycleGate<Native> { it.close() }
        assertTrue(gate.initialize { native })
        try {
            val decode = pool.submit { gate.use { it.decoding.incrementAndGet(); entered.countDown(); finish.awaitBarrier(); it.decoding.decrementAndGet() } }
            entered.awaitBarrier()
            gate.invalidate()
            val close = pool.submit { closing.countDown(); gate.close() }
            closing.awaitBarrier()
            assertEquals(0, native.closes.get())
            assertNull(gate.useAfterInvalidationProbe())
            finish.countDown()
            decode.get(3, TimeUnit.SECONDS); close.get(3, TimeUnit.SECONDS)
            gate.close()
            assertEquals(1, native.closes.get())
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    // State probe must not acquire the worker-only operations monitor while decode is blocked.
    private fun NativeLifecycleGate<Native>.useAfterInvalidationProbe(): Any? = if (hasResource()) this else null

    @Test fun oldServiceCleanupCannotOverwriteOrCloseRestartedService() {
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val old = Native(); val replacement = Native()
        val first = NativeLifecycleGate<Native> { it.close() }
        val next = NativeLifecycleGate<Native> { it.close() }
        try {
            val init = pool.submit<Boolean> { first.initialize { entered.countDown(); finish.awaitBarrier(); old } }
            entered.awaitBarrier(); first.invalidate()
            assertTrue(next.initialize { replacement })
            finish.countDown(); assertFalse(init.get(3, TimeUnit.SECONDS))
            first.close(); first.close()
            assertTrue(next.hasResource()); assertEquals(0, replacement.closes.get())
            assertFalse(first.initialize { error("closed gate must not construct") })
            next.close(); next.close()
            assertEquals(1, old.closes.get()); assertEquals(1, replacement.closes.get())
        } finally { finish.countDown(); pool.shutdownNow() }
    }
}
