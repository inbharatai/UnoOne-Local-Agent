package com.unoone.agent

import com.unoone.agent.overlay.ForegroundEvidenceReceipt as Receipt
import org.junit.Assert.*
import org.junit.Test

class ForegroundEvidenceReceiptTest {
    private class Source : Receipt.Source {
        var root = Receipt.Identity("com.example.notes", 7)
        var before: Receipt.Identity? = root
        var after: Receipt.Identity? = root
        var reads = 0
        var epoch = 4L
        var events = 10L
        var mutateEpoch = false
        var mutateSequence = false
        override fun generation() = epoch
        override fun sequence() = events
        override fun now() = 100L
        override fun activeApplication(): Receipt.Identity? {
            if (reads++ == 0) return before
            if (mutateEpoch) epoch++
            if (mutateSequence) events++
            return after
        }
        override fun activeRoot() = root
    }
    @Test fun mixedPackageOrWindowCannotConstructReceipt() {
        assertNull(Receipt.capture(Source().apply { before = Receipt.Identity("other.app", 7) }))
        assertNull(Receipt.capture(Source().apply { after = Receipt.Identity("com.example.notes", 8) }))
    }
    @Test fun changingEpochOrEventsRejectsReceipt() {
        assertNull(Receipt.capture(Source().apply { mutateEpoch = true }))
        assertNull(Receipt.capture(Source().apply { mutateSequence = true }))
    }
    @Test fun staleFutureAndNewEpochRejectAdmission() {
        val receipt = Receipt.capture(Source())!!
        assertTrue(receipt.isFresh(15100, 4))
        assertFalse(receipt.isFresh(15101, 4))
        assertFalse(receipt.isFresh(99, 4))
        assertFalse(receipt.isFresh(100, 5))
    }
    @Test fun ownAppRequiresActualApplicationWindow() {
        val own = Source().apply {
            root = Receipt.Identity("com.unoone.agent", 9); before = root; after = root
        }
        assertEquals("com.unoone.agent", Receipt.capture(own)!!.packageName)
        assertNull(Receipt.capture(Source().apply { before = null })) // overlay/non-application
    }
    @Test fun eventPackageIsNotAnAuthorityInput() {
        // Event sequence is only a race detector: no last-event package can replace root identity.
        val receipt = Receipt.capture(Source().apply { events = 999 })!!
        assertEquals("com.example.notes", receipt.packageName)
        assertEquals(7, receipt.windowId)
        assertEquals(999L, receipt.eventSequence)
    }
}
