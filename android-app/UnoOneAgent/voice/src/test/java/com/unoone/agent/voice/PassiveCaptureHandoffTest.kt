package com.unoone.agent.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PassiveCaptureHandoffTest {
    @Test fun midChunkAckPrecedesCaptureAndHide() = runBlocking {
        val handoff = PassiveCaptureHandoff()
        val owner = Any()
        val entered = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        var captureReady = 0
        var hidden = false
        assertTrue(handoff.claim(owner))
        val worker = async {
            if (handoff.acknowledge(owner) { entered.complete(Unit); drained.await(); true }) {
                captureReady++
                hidden = true
            }
        }
        entered.await()
        assertEquals(0, captureReady)
        assertFalse(hidden)
        drained.complete(Unit)
        worker.await()
        assertEquals(1, captureReady)
        assertTrue(hidden)
    }
    @Test fun oldReleaseCannotResumeNewPause() {
        val handoff = PassiveCaptureHandoff()
        val old = Any(); val fresh = Any()
        assertTrue(handoff.claim(old))
        assertTrue(handoff.release(old))
        assertTrue(handoff.claim(fresh))
        assertFalse(handoff.release(old))
        assertTrue(handoff.paused())
    }
    @Test fun deniedClaimAndFailedDrainNeverSignalReady() = runBlocking {
        val handoff = PassiveCaptureHandoff()
        val owner = Any()
        var ready = 0
        assertTrue(handoff.claim(owner))
        if (handoff.claim(Any())) ready++
        if (handoff.acknowledge(owner) { false }) ready++
        assertEquals(0, ready)
    }
    @Test fun queuedSpeechAfterStopSuppressedButFreshAllowed() {
        val generation = QueuedSpeechGeneration()
        val queued = generation.capture()
        generation.stop()
        assertFalse(generation.current(queued))
        assertTrue(generation.current(generation.capture()))
    }
}
