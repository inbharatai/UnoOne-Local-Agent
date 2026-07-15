package com.unoone.agent.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM test for [Capability] — the eyes-free main-page capability routing. Pure data; verifies each
 * large button maps to the correct handler and carries a non-blank TalkBack label (the live tap +
 * TalkBack announcement are device-time gates, not JVM-assertable).
 */
class CapabilityTest {

    @Test
    fun everyCapabilityHasATalkBackLabel() {
        for (cap in Capability.entries) {
            assertTrue(
                "${cap.label} must have a non-blank TalkBack label for eyes-free use",
                cap.talkBackLabel.isNotBlank()
            )
            assertTrue(
                "${cap.label} must have a non-blank short label",
                cap.label.isNotBlank()
            )
        }
    }

    @Test
    fun listenRoutesToStartListening() {
        assertEquals(CapabilityHandler.START_LISTENING, Capability.LISTEN.handler)
    }

    @Test
    fun blindAidRoutesToToggle() {
        assertEquals(CapabilityHandler.TOGGLE_BLIND_AID, Capability.BLIND_AID.handler)
    }

    @Test
    fun readScreenRoutesToReadScreenHandler() {
        assertEquals(CapabilityHandler.READ_SCREEN, Capability.READ_SCREEN.handler)
    }

    @Test
    fun secureBrowserRoutesToOpenSecureBrowser() {
        assertEquals(CapabilityHandler.OPEN_SECURE_BROWSER, Capability.SECURE_BROWSER.handler)
    }

    @Test
    fun allFourCapabilitiesPresent() {
        // The eyes-free surface exposes exactly these four primary actions.
        assertEquals(4, Capability.entries.size)
        assertTrue(Capability.entries.map { it.handler }.toSet().size == 4)
    }
}