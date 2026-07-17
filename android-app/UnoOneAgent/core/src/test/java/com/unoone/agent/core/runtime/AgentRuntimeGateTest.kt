package com.unoone.agent.core.runtime

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeGateTest {
    @After
    fun restoreDefault() {
        AgentRuntimeGate.setEnabled(true)
    }

    @Test
    fun transitionIsImmediateAndReversibleOnlyByExplicitSetter() {
        AgentRuntimeGate.setEnabled(false)
        assertFalse(AgentRuntimeGate.isEnabled())

        AgentRuntimeGate.setEnabled(true)
        assertTrue(AgentRuntimeGate.isEnabled())
    }
}
