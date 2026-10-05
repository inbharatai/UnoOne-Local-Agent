package com.unoone.agent.voice

import com.unoone.agent.voice.recorder.PcmRms
import com.unoone.agent.voice.tts.awaitPcmPlayback
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechEngineRegressionTest {
    private fun pcm(vararg values: Int): ByteArray = values.flatMap { listOf(it.toByte(), (it shr 8).toByte()) }.toByteArray()

    @Test fun signedRmsAndOddTail() {
        assertEquals(0f, PcmRms.amplitude(ByteArray(20)), 0f)
        assertEquals(1f / 32768, PcmRms.amplitude(pcm(-1, 1)), 0.0000001f)
        assertEquals(32767f / 32768, PcmRms.amplitude(pcm(-32767, 32767)), 0.000001f)
        assertEquals(1f, PcmRms.amplitude(pcm(-32768)), 0f)
        assertEquals(PcmRms.amplitude(pcm(-1, 1)), PcmRms.amplitude(pcm(-1, 1) + byteArrayOf(127)), 0f)
        assertEquals(0f, PcmRms.amplitude(byteArrayOf(127)), 0f)
    }

    @Test fun lowSineRemainsBelowSpeechThreshold() {
        val wave = IntArray(1600) { (sin(it * 2 * Math.PI / 40) * 0.005 * 32768).toInt() }
        assertEquals((0.005 / sqrt(2.0)).toFloat(), PcmRms.amplitude(pcm(*wave)), 0.00004f)
        assertTrue(PcmRms.amplitude(pcm(-1, 1)) < 0.018f)
    }

    @Test fun playbackFailurePropagatesAndCleansUp() = runBlocking {
        var cleaned = false
        val error = Result.Error("AudioTrack failed")
        val result = awaitPcmPlayback(100, { true }, { cleaned = true }) { error to 0L }
        assertSame(error, result)
        assertTrue(cleaned)
    }

    @Test fun longPlaybackIsNotFalseSuccess() = runBlocking {
        var cleaned = false
        val result = awaitPcmPlayback(0, { true }, { cleaned = true }) { Result.Success(Unit) to 45_000L }
        assertTrue(result is Result.Error)
        assertTrue(cleaned)
    }

    @Test fun cancellationReleasesPlayback() = runBlocking {
        var cleaned = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            awaitPcmPlayback(30_000, { true }, { cleaned = true }) {
                started.complete(Unit)
                Result.Success(Unit) to 20_000L
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(cleaned)
    }

    @Test fun thrownSynthesisStillReleasesPlayback() = runBlocking {
        var cleaned = 0
        try {
            awaitPcmPlayback(100, { true }, { cleaned++ }) { error("native failure") }
            fail("Expected synthesis exception")
        } catch (_: IllegalStateException) {
            assertEquals(1, cleaned)
        }
    }

    @Test fun interruptedEpochCannotSucceed() = runBlocking {
        val result = awaitPcmPlayback(0, { false }, {}) { Result.Success(Unit) to 0L }
        assertTrue(result is Result.Error)
    }
}
