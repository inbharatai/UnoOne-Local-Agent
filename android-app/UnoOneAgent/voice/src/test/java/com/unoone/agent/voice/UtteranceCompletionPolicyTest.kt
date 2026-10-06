package com.unoone.agent.voice

import org.junit.Assert.*
import org.junit.Test
import com.unoone.agent.voice.UtteranceCompletionPolicy.TerminalReason

class UtteranceCompletionPolicyTest {
    @Test fun cutoffHasZeroDispatchAndApproval() {
        var dispatch = 0
        var approve = 0
        for (text in listOf("send message", "yes", "confirm")) {
            if (UtteranceCompletionPolicy.eligible(TerminalReason.DURATION_CUTOFF, text)) {
                dispatch++
                approve++
            }
        }
        assertEquals(0, dispatch)
        assertEquals(0, approve)
    }
    @Test fun completedAndManualRemainEligible() {
        assertTrue(UtteranceCompletionPolicy.eligible(TerminalReason.SILENCE, "open notes"))
        assertTrue(UtteranceCompletionPolicy.eligible(TerminalReason.MANUAL_DONE, "confirm"))
    }
    @Test fun emptyAndCancelledNeverEligible() {
        for (reason in TerminalReason.values()) {
            assertFalse(UtteranceCompletionPolicy.eligible(reason, " "))
            assertFalse(UtteranceCompletionPolicy.eligible(reason, null))
        }
        assertFalse(UtteranceCompletionPolicy.eligible(TerminalReason.CANCELLED, "confirm"))
    }
    @Test fun exactStopRemainsIndependentOfOrdinaryAdmission() {
        assertTrue(VoiceControlPolicy.isStop("UnoOne stop"))
        assertTrue(VoiceControlPolicy.isStop("cancel"))
        assertFalse(VoiceControlPolicy.isStop("stop after sending"))
    }
}
