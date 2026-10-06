package com.unoone.agent.voice

import org.junit.Assert.*
import org.junit.Test

class PassiveMonitoringPolicyTest {
    @Test fun busyDoesNotOwnMicrophone() {
        assertTrue(PassiveMonitoringPolicy.canCapture(false, false, false, false, false))
        assertTrue(PassiveMonitoringPolicy.stopOnly(true, false))
        assertFalse(PassiveMonitoringPolicy.ordinaryAllowed(true, false, false))
    }
    @Test fun playbackRequiresActualEnabledAecAndOnlyWakeStop() {
        assertFalse(PassiveMonitoringPolicy.canCapture(true, false, false, false, false))
        assertFalse(PassiveMonitoringPolicy.canCapture(true, true, false, false, false))
        assertTrue(PassiveMonitoringPolicy.canCapture(true, true, true, false, false))
        assertTrue(PassiveMonitoringPolicy.stopOnly(true, true))
        assertFalse(EmergencyStopPolicy.accepts("stop", true, true, true, false, false))
        assertTrue(EmergencyStopPolicy.accepts("Uno stop", true, true, true, false, false))
        assertFalse(EmergencyStopPolicy.accepts("Uno open Chrome", true, true, true, false, false))
    }
    @Test fun foregroundAdmissionAndDrainExcludeAllPassiveCapture() {
        assertFalse(PassiveMonitoringPolicy.canCapture(false, true, true, false, true))
        assertFalse(PassiveMonitoringPolicy.ordinaryAllowed(false, false, true))
        assertFalse(PassiveMonitoringPolicy.foregroundIdle(true, false))
        assertFalse(PassiveMonitoringPolicy.foregroundIdle(false, true))
    }
    @Test fun backgroundLeaseIsNotAnOverlayHidePrecondition() {
        val lease = MicrophoneLease()
        val background = Any()
        assertTrue(lease.acquire(background))
        assertFalse(lease.isIdle())
        assertTrue(PassiveMonitoringPolicy.foregroundIdle(false, false))
        lease.release(background, true, true)
    }
    @Test fun stopCancellationIsSynchronous() {
        var cancelled = false
        assertTrue(VoiceControlPolicy.routeStop("Uno stop") { cancelled = true })
        assertTrue(cancelled)
    }
}
