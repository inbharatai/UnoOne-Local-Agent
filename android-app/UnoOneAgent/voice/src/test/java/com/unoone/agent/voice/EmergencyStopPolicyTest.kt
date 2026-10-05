package com.unoone.agent.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopPolicyTest {
    private fun accepts(text: String, speaking: Boolean = true, available: Boolean = true,
                        enabled: Boolean = true, call: Boolean = false, owned: Boolean = false) =
        EmergencyStopPolicy.accepts(text, speaking, available, enabled, call, owned)

    @Test fun playbackRequiresAvailableAndEnabledEffect() {
        assertTrue(accepts("Uno stop"))
        assertTrue(accepts("UnoOne cancel"))
        assertFalse(accepts("Uno stop", available = false))
        assertFalse(accepts("Uno stop", enabled = false))
        assertFalse(accepts("Uno stop", available = false, enabled = false))
    }
    @Test fun playbackRequiresExactWakeQualifiedStop() {
        listOf("stop", "cancel", "Uno confirm", "Uno open camera", "Uno stop and send",
            "Uno stop; confirm", "please Uno stop", "Uno cancel then approve").forEach {
            assertFalse(it, accepts(it))
        }
    }
    @Test fun callAndOtherMicrophoneOwnerAlwaysBlock() {
        for (speaking in listOf(true, false)) {
            assertFalse(accepts("Uno stop", speaking = speaking, call = true))
            assertFalse(accepts("Uno stop", speaking = speaking, owned = true))
        }
    }
    @Test fun inferenceStopWithoutPlaybackRemainsAvailableWithoutAec() {
        assertTrue(accepts("stop", speaking = false, available = false, enabled = false))
        assertTrue(accepts("cancel", speaking = false, available = false, enabled = false))
        assertFalse(accepts("confirm", speaking = false))
        assertFalse(accepts("stop and open camera", speaking = false))
    }
}
