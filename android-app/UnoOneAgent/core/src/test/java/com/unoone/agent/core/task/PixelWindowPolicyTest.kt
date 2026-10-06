package com.unoone.agent.core.task

import org.junit.Assert.*
import org.junit.Test

class PixelWindowPolicyTest {
    @Test fun onlyTheAdmittedApplicationWindowIsAllowed() {
        assertTrue(PixelWindowPolicy.allows(listOf(7 to 1), 7, 1))
        assertFalse(PixelWindowPolicy.allows(emptyList(), 7, 1))
        assertFalse(PixelWindowPolicy.allows(listOf(8 to 1), 7, 1))
        assertFalse(PixelWindowPolicy.allows(listOf(7 to 4), 7, 1))
    }
    @Test fun overlaysImeSystemAndSecondAppAllRejectBeforeRecognition() {
        for (foreignType in listOf(1, 2, 3, 4, 5, 99)) {
            val windows = listOf(7 to 1, 8 to foreignType)
            var recognitions = 0
            if (PixelWindowPolicy.allows(windows, 7, 1)) recognitions++
            assertEquals(0, recognitions)
        }
    }
    @Test fun overlayAppearingAfterCaptureCannotReuseInitialAdmission() {
        assertTrue(PixelWindowPolicy.allows(listOf(7 to 1), 7, 1))
        assertFalse(PixelWindowPolicy.allows(listOf(7 to 1, 9 to 4), 7, 1))
    }
}
