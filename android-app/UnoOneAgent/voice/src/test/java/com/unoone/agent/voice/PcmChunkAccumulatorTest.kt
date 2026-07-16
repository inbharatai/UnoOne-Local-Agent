package com.unoone.agent.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmChunkAccumulatorTest {

    @Test
    fun `retains every command chunk in order`() {
        val accumulator = PcmChunkAccumulator(maxBytes = 8)

        accumulator.add(byteArrayOf(1, 2, 3))
        accumulator.add(byteArrayOf(4, 5))
        accumulator.add(byteArrayOf(6, 7, 8))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), accumulator.toByteArray())
        assertTrue(accumulator.isFull)
    }

    @Test
    fun `hard cap truncates excess audio and clear rearms the buffer`() {
        val accumulator = PcmChunkAccumulator(maxBytes = 4)

        assertEquals(4, accumulator.add(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertEquals(0, accumulator.add(byteArrayOf(7)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), accumulator.toByteArray())

        accumulator.clear()
        assertEquals(2, accumulator.add(byteArrayOf(9, 10)))
        assertArrayEquals(byteArrayOf(9, 10), accumulator.toByteArray())
    }
}
