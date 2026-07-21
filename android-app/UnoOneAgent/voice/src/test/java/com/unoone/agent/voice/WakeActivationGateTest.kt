package com.unoone.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeActivationGateTest {
    @Test
    fun `one speech burst cannot activate twice`() {
        val gate = WakeActivationGate(cooldownMs = 1_800)

        assertTrue(gate.tryActivate(10_000))
        assertFalse(gate.tryActivate(10_500))
        assertFalse(gate.tryActivate(11_799))
        assertTrue(gate.tryActivate(11_800))
    }

    @Test
    fun `reset permits a fresh activation`() {
        val gate = WakeActivationGate()
        assertTrue(gate.tryActivate(1_000))
        gate.reset()
        assertTrue(gate.tryActivate(1_001))
    }
}
