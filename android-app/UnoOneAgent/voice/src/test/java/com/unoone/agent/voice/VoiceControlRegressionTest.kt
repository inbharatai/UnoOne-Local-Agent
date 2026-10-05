package com.unoone.agent.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceControlRegressionTest {
    @Test fun stopRoutesImmediatelyWithoutCommandQueue() {
        val events = mutableListOf<String>()
        assertTrue(VoiceControlPolicy.routeStop("Uno, stop!") { events += "cancel" })
        assertEquals(listOf("cancel"), events)
        assertFalse(VoiceControlPolicy.routeStop("Uno stop and send it") { events += "bad" })
        assertEquals(listOf("cancel"), events)
    }

    @Test fun stopRequiresExactBoundedEnvelope() {
        listOf("stop", "CANCEL", "UnoOne cancel.", "यूनो रुको", "यूनो वन रद्द").forEach {
            assertTrue(it, VoiceControlPolicy.isStop(it))
        }
        listOf("unstoppable", "please stop", "Uno stop sending messages", "someone said stop", "unostop").forEach {
            assertFalse(it, VoiceControlPolicy.isStop(it))
        }
    }

    @Test fun wakePrefixPreservesPayloadPunctuation() {
        assertEquals("send hello, world!", WakePhraseMatcher.match("Uno, send hello, world!")?.command)
        assertEquals("confirm", VoiceControlPolicy.payload("Uno one: confirm"))
        assertNull(WakePhraseMatcher.match("someone said Uno stop"))
    }

    @Test fun callModesBlockAndNormalModesResume() {
        assertTrue(VoiceCapturePolicy.isCallAudioActive(2))
        assertTrue(VoiceCapturePolicy.isCallAudioActive(3))
        assertFalse(VoiceCapturePolicy.isCallAudioActive(0))
        assertFalse(VoiceCapturePolicy.isCallAudioActive(1))
    }

    @Test fun confirmationAcceptsWakePrefixButNotCompoundApproval() {
        assertEquals(true, VoiceConfirmationPolicy.decision("Uno confirm", true))
        assertEquals(false, VoiceConfirmationPolicy.decision("Uno cancel", true))
        assertNull(VoiceConfirmationPolicy.decision("Uno confirm and send another", true))
    }
}
