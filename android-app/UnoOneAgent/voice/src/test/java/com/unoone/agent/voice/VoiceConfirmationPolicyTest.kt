package com.unoone.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConfirmationPolicyTest {
    @Test
    fun ordinaryConfirmationAcceptsExactEnglishAndHindiAcknowledgements() {
        assertEquals(true, VoiceConfirmationPolicy.decision("yes please", requiresExplicitConfirm = false))
        assertEquals(true, VoiceConfirmationPolicy.decision("हाँ करो", requiresExplicitConfirm = false))
        assertEquals(false, VoiceConfirmationPolicy.decision("cancel", requiresExplicitConfirm = false))
    }

    @Test
    fun strongConfirmationRequiresTheExplicitConfirmWord() {
        assertEquals(null, VoiceConfirmationPolicy.decision("yes", requiresExplicitConfirm = true))
        assertEquals(true, VoiceConfirmationPolicy.decision("confirm", requiresExplicitConfirm = true))
        assertEquals(true, VoiceConfirmationPolicy.decision("कन्फर्म करो", requiresExplicitConfirm = true))
        assertEquals(false, VoiceConfirmationPolicy.decision("नहीं", requiresExplicitConfirm = true))
    }

    @Test
    fun aNewCommandCannotAuthorizeAnExistingAction() {
        assertEquals(null, VoiceConfirmationPolicy.decision("yes open chrome", requiresExplicitConfirm = false))
    }

    @Test
    fun promptTellsTheUserWhichWakePhraseAndDecisionToSay() {
        assertEquals(
            "Security confirmation needed. Say Uno confirm to continue, or say Uno cancel.",
            VoiceConfirmationPolicy.prompt(requiresExplicitConfirm = true)
        )
    }
}
