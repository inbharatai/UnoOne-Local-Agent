package com.unoone.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentRuntimeTest {
    @Test
    fun stateTransitionsAreObservableAndDisableClearsPrivateState() {
        val match = requireNotNull(WakePhraseMatcher.match("Uno open WhatsApp"))
        VoiceAgentRuntime.recordWake("Uno open WhatsApp", match)
        VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "test")
        assertEquals(VoiceAgentState.PROCESSING, VoiceAgentRuntime.diagnostics.value.state)
        assertTrue(VoiceAgentRuntime.diagnostics.value.extractedCommand.isNotBlank())

        VoiceAgentRuntime.clearForDisable()

        val disabled = VoiceAgentRuntime.diagnostics.value
        assertEquals(VoiceAgentState.DISABLED, disabled.state)
        assertEquals("", disabled.rawTranscript)
        assertEquals("", disabled.extractedCommand)
    }
}
