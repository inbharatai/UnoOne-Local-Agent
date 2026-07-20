package com.unoone.agent.execution

import com.unoone.agent.phonecontrol.LaunchAttempt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundLaunchVerifierTest {
    private val whatsappAttempt = LaunchAttempt(
        requestedPackage = "com.whatsapp.w4b",
        expectedForegroundPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    )

    @Test
    fun acceptsOnlyAnExpectedExactPackage() {
        assertTrue(ForegroundLaunchVerifier.matches(whatsappAttempt, "com.whatsapp.w4b"))
        assertTrue(ForegroundLaunchVerifier.matches(whatsappAttempt, "com.whatsapp"))
        assertFalse(ForegroundLaunchVerifier.matches(whatsappAttempt, "com.whatsapp.fake"))
        assertFalse(ForegroundLaunchVerifier.matches(whatsappAttempt, "com.miui.home"))
    }

    @Test
    fun missingOrBlankObservationNeverPasses() {
        assertFalse(ForegroundLaunchVerifier.matches(whatsappAttempt, null))
        assertFalse(ForegroundLaunchVerifier.matches(whatsappAttempt, ""))
        assertFalse(ForegroundLaunchVerifier.matches(whatsappAttempt, "   "))
    }

    @Test
    fun failuresExplainAccessibilityAndBackgroundStartRecovery() {
        assertTrue(
            ForegroundLaunchVerifier.unavailableMessage("WhatsApp")
                .contains("Accessibility access is off")
        )
        assertTrue(
            ForegroundLaunchVerifier.mismatchMessage("WhatsApp")
                .contains("background-start settings")
        )
    }
}
