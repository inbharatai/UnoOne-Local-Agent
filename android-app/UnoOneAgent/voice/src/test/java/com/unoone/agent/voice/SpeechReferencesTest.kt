package com.unoone.agent.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SpeechReferencesTest {
    @Test fun oldFinallyCannotReleaseSuccessor() {
        val refs = SpeechReferences()
        val a = refs.acquire()
        val barrier = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val old = Thread { barrier.await(); refs.release(a); ended.countDown() }
        old.start()
        refs.clear()
        val b = refs.acquire()
        barrier.countDown()
        ended.await()
        assertTrue(refs.isBusy())
        refs.release(b)
        assertFalse(refs.isBusy())
        old.join()
    }

    @Test fun multipleProducersAndDuplicateFinishes() {
        val refs = SpeechReferences()
        val a = refs.acquire()
        val b = refs.acquire()
        refs.release(a)
        refs.release(a)
        assertTrue(refs.isBusy())
        refs.release(b)
        assertFalse(refs.isBusy())
    }

    @Test fun legacyReleaseNeverReleasesOwnedSpeech() {
        val refs = SpeechReferences()
        refs.beginLegacy()
        refs.clear()
        val b = refs.acquire()
        refs.endLegacy()
        refs.endLegacy()
        assertTrue(refs.isBusy())
        refs.release(b)
        assertFalse(refs.isBusy())
    }

    @Test fun balancedLegacyReferencesSurviveClearWithoutEpochGuessing() {
        val refs = SpeechReferences()
        refs.beginLegacy()
        refs.clear()
        refs.beginLegacy()
        refs.endLegacy()
        assertTrue(refs.isBusy())
        refs.endLegacy()
        assertFalse(refs.isBusy())
    }
}
