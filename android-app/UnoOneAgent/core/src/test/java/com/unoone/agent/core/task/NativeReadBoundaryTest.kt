package com.unoone.agent.core.task

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeReadBoundaryTest {
    private class Host {
        var pkg = "a"
        var event = 1
        var reads = 0
        fun identity() = pkg to event
        fun capture(): String { reads++; return "private-content" }
    }
    @Test fun wrongAppBeforeCaptureReadsNothing() = runBlocking {
        val host = Host().apply { pkg = "b" }
        val result = runCatching { NativeReadBoundary(setOf("a"), host::identity) {}.read { host.capture() } }
        assertTrue(result.isFailure); assertEquals(0, host.reads)
    }
    @Test fun switchAfterCaptureNeverReturnsContent() = runBlocking {
        val host = Host()
        val boundary = NativeReadBoundary(setOf("a"), host::identity) {}
        val result = runCatching { boundary.read { val text = host.capture(); host.pkg = "b"; host.event++; text } }
        assertTrue(result.isFailure); assertEquals(1, host.reads)
    }
    @Test fun activeBlindAidStopRejectsNewFeedback() {
        var active = true; var epoch = 1L
        val producerEpoch = epoch
        assertTrue(acceptsBlindAidFeedback(active, producerEpoch, epoch, true))
        epoch++; active = false
        assertFalse(acceptsBlindAidFeedback(active, producerEpoch, epoch, true))
        active = true
        assertFalse(acceptsBlindAidFeedback(active, producerEpoch, epoch, true))
    }
}
