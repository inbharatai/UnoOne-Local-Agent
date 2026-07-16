package com.unoone.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadGateTest {

    @Test
    fun `duplicate load is rejected until active load releases`() {
        val gate = ModelLoadGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isInFlight())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertFalse(gate.isInFlight())
        assertTrue(gate.tryAcquire())
    }
}
