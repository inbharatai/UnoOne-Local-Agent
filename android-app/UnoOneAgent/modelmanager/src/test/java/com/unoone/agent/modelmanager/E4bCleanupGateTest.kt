package com.unoone.agent.modelmanager

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E4bCleanupGateTest {
    private val complete = E4bQualificationRecord(
        E4bCleanupGate.SHA256,
        E4bCleanupGate.SIZE_BYTES,
        loaded = true,
        strictSelfTestPassed = true,
        backend = "CPU",
        evaluationPassed = true,
        sustainedRunCompleted = true,
        noCrashAnrOom = true,
        deviceBuild = "Xiaomi 14/test-build",
        qualifiedAtMs = 1L
    )

    @Test fun `every qualification gate and user approval is required`() {
        assertTrue(E4bCleanupGate.rejectionReason(null, true)!!.contains("No E4B"))
        assertTrue(E4bCleanupGate.rejectionReason(complete, false)!!.contains("approval"))
        assertTrue(E4bCleanupGate.rejectionReason(complete.copy(strictSelfTestPassed = false), true)!!.contains("self-test"))
        assertTrue(E4bCleanupGate.rejectionReason(complete.copy(sustainedRunCompleted = false), true)!!.contains("Sustained"))
        assertTrue(E4bCleanupGate.rejectionReason(complete.copy(noCrashAnrOom = false), true)!!.contains("stability"))
        assertNull(E4bCleanupGate.rejectionReason(complete, true))
    }
}
