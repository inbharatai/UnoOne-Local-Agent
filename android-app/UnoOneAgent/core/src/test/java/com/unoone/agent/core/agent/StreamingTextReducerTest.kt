package com.unoone.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the streaming reducer: cumulative-vs-delta snapshot handling, delta emission,
 * and final-text convergence. The LiteRT-LM Flow that feeds snapshots and the UI/TTS surfacing are
 * device-time-only; these tests pin the deterministic control contract.
 */
class StreamingTextReducerTest {

    @Test
    fun cumulativeSnapshotsEmitSuffixDeltas() {
        val r = StreamingTextReducer()
        assertEquals("Hel", r.onSnapshot("Hel"))
        assertEquals("lo", r.onSnapshot("Hello"))
        assertEquals(" wor", r.onSnapshot("Hello wor"))
        assertEquals("ld", r.onSnapshot("Hello world"))
        assertEquals("Hello world", r.fullText())
    }

    @Test
    fun firstSnapshotEmitsFull() {
        val r = StreamingTextReducer()
        assertEquals("Hello", r.onSnapshot("Hello"))
        assertEquals("Hello", r.fullText())
    }

    @Test
    fun emptySnapshotsEmitNothingAndDoNotMutate() {
        val r = StreamingTextReducer()
        assertEquals("", r.onSnapshot(""))
        assertEquals("", r.onSnapshot(""))
        assertEquals("", r.fullText())
        assertEquals("Hi", r.onSnapshot("Hi"))
        assertEquals("", r.onSnapshot(""))
        assertEquals("Hi", r.fullText())
    }

    @Test
    fun deltaSemanticsAppendNonExtensionSnapshots() {
        // Each snapshot is just the new tokens (does not extend the current text) → appended as deltas.
        val r = StreamingTextReducer()
        assertEquals("Hel", r.onSnapshot("Hel"))
        assertEquals("lo", r.onSnapshot("lo"))
        assertEquals(" world", r.onSnapshot(" world"))
        assertEquals("Hello world", r.fullText())
    }

    @Test
    fun mixedCumulativeThenResetAppends() {
        val r = StreamingTextReducer()
        assertEquals("Hello", r.onSnapshot("Hello"))
        // A non-extension snapshot after cumulative accumulation is treated as a delta (model reset).
        assertEquals("Sorry", r.onSnapshot("Sorry"))
        assertEquals("HelloSorry", r.fullText())
    }

    @Test
    fun resetClearsAccumulator() {
        val r = StreamingTextReducer()
        r.onSnapshot("Hello")
        assertEquals("Hello", r.fullText())
        r.reset()
        assertEquals("", r.fullText())
        // After reset, the next snapshot is treated as the first (full emit).
        assertEquals("World", r.onSnapshot("World"))
    }

    @Test
    fun finalTextConvergesUnderBothSemantics() {
        // Cumulative path
        val cum = StreamingTextReducer()
        listOf("H", "He", "Hel", "Hell", "Hello").forEach { cum.onSnapshot(it) }
        // Delta path
        val del = StreamingTextReducer()
        listOf("H", "e", "l", "l", "o").forEach { del.onSnapshot(it) }
        assertEquals("Hello", cum.fullText())
        assertEquals("Hello", del.fullText())
    }

    @Test
    fun deltaIsExactlyTheNewSuffixForCumulative() {
        val r = StreamingTextReducer()
        r.onSnapshot("abc")
        assertEquals("def", r.onSnapshot("abcdef"))
        assertEquals("abcdef", r.fullText())
    }
}