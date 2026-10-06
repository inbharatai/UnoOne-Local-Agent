package com.unoone.agent.voice

import org.junit.Assert.*
import org.junit.Test

class ForegroundTaskOwnershipTest {
    @Test fun runtimeAndLegacyUiMayNestWithoutEarlyRelease() {
        VoiceService.clearAudioOwnership()
        VoiceService.beginForegroundTask() // legacy UI
        try {
            VoiceService.beginForegroundTask() // command worker, including TaskBoard entry
            try { assertTrue(VoiceService.isForegroundTaskActive()) }
            finally { VoiceService.endForegroundTask() }
            assertTrue(VoiceService.isForegroundTaskActive())
        } finally { VoiceService.endForegroundTask() }
        assertFalse(VoiceService.isForegroundTaskActive())
    }

    @Test fun exceptionalWorkerStillBalancesReference() {
        VoiceService.clearAudioOwnership()
        try {
            VoiceService.beginForegroundTask()
            try { throw IllegalStateException("worker failure") }
            finally { VoiceService.endForegroundTask() }
        } catch (_: IllegalStateException) { }
        assertFalse(VoiceService.isForegroundTaskActive())
    }
}
