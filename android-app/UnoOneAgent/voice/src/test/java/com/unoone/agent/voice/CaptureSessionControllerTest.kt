package com.unoone.agent.voice

import com.unoone.agent.core.model.Result
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the exact session controller invoked by VoiceModule, with injectable drain ACK barriers. */
class CaptureSessionControllerTest {
    private fun start(c: CaptureSessionController, s: CaptureSessionController.Session) =
        c.acquire(s, {}) { Result.Success(Unit) }

    @Test fun globalStopAThenBRejectsLateAFinally() = runBlocking {
        val c = CaptureSessionController(); val a = CaptureSessionController.Session(); val b = CaptureSessionController.Session()
        assertTrue(start(c, a) is Result.Success)
        c.revoke(a)
        val ack = CompletableDeferred<Boolean>()
        val cleanup = launch { c.drain(a) { ack.await() } }
        yield()
        assertTrue(a.revoked.get()); assertTrue(start(c, b) is Result.Error)
        ack.complete(true); cleanup.join()
        assertTrue(start(c, b) is Result.Success)
        c.drain(a) { fail("A must not touch B native resources"); true }
        assertSame(b, c.current())
    }

    @Test fun shutdownMidSttWaitsForProductionSessionUse() = runBlocking {
        val c = CaptureSessionController(); val a = CaptureSessionController.Session()
        start(c, a)
        val decoding = CompletableDeferred<Unit>(); val decoded = CompletableDeferred<Unit>()
        val task = launch { c.use(a, {}) { decoding.complete(Unit); decoded.await() } }
        decoding.await()
        assertSame(a, c.shutdown()); assertTrue(a.revoked.get())
        var destroyed = false
        val release = launch { c.drain(a) { destroyed = true; true } }
        yield(); assertFalse(destroyed)
        assertTrue(start(c, CaptureSessionController.Session()) is Result.Error)
        decoded.complete(Unit); task.join(); release.join()
        assertTrue(destroyed); assertNull(c.current())
        assertTrue(start(c, CaptureSessionController.Session()) is Result.Error)
    }

    @Test fun recognizerDestroyFailureRetainsSessionAndProcessLease() = runBlocking {
        val c = CaptureSessionController(); val a = CaptureSessionController.Session(); val lease = MicrophoneLease()
        start(c, a); assertTrue(lease.acquire(a))
        c.revoke(a)
        c.drain(a) { lease.release(a, destructionAcknowledged = false, childJoined = true) }
        assertSame(a, c.current()); assertFalse(lease.isIdle())
        assertFalse(lease.release(a, destructionAcknowledged = true, childJoined = false))
        assertTrue(start(c, CaptureSessionController.Session()) is Result.Error)
        c.drain(a) { lease.release(a, destructionAcknowledged = true, childJoined = true) }
        assertNull(c.current()); assertTrue(lease.isIdle())
    }

    @Test fun pcmAndRecognizerUseSameLeaseAndOldNonceCannotReleaseSuccessor() {
        val lease = MicrophoneLease(); val pcm = Any(); val recognizer = Any()
        assertTrue(lease.acquire(pcm)); assertFalse(lease.acquire(recognizer))
        assertFalse(lease.release(pcm, true, false)); assertTrue(lease.release(pcm, true, true))
        assertTrue(lease.acquire(recognizer)); assertFalse(lease.release(pcm, true, true))
        assertFalse(lease.isIdle())
    }

    @Test fun shutdownDuringStartingRevokesPendingOwnerWithoutBlocking() {
        val c = CaptureSessionController(); val a = CaptureSessionController.Session()
        val entered = java.util.concurrent.CountDownLatch(1); val ack = java.util.concurrent.CountDownLatch(1)
        val thread = Thread { c.acquire(a, {}) { entered.countDown(); check(ack.await(2, java.util.concurrent.TimeUnit.SECONDS)); Result.Success(Unit) } }
        thread.start(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try { assertSame(a, c.shutdown()); assertTrue(a.revoked.get()) }
        finally { ack.countDown(); thread.join(2000) }
        assertTrue(start(c, CaptureSessionController.Session()) is Result.Error)
    }
}
