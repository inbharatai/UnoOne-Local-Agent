package com.unoone.agent

import com.unoone.agent.browser.BrowserLeasePolicy
import com.unoone.agent.core.model.BrainModelRegistry
import org.junit.Assert.*
import org.junit.Test

class BrowserLeasePolicyTest {
    @Test fun noPhoneMayLoadAfterNativeBarrierEvenWhileOwnLeaseIsHeld() {
        assertTrue(BrowserLeasePolicy.canAcquire(false))
        assertTrue(BrowserLeasePolicy.canLoadBrowser(true, false))
        assertNull(BrowserLeasePolicy.restoration(null, true, false))
    }
    @Test fun alreadyLeasedCannotAcquire() {
        assertFalse(BrowserLeasePolicy.canAcquire(true))
    }
    @Test fun unloadRefusalOrResidualEngineNeverPermitsBrowser() {
        assertFalse(BrowserLeasePolicy.canLoadBrowser(false, true))
        assertFalse(BrowserLeasePolicy.canLoadBrowser(false, false))
        assertFalse(BrowserLeasePolicy.canLoadBrowser(true, true))
    }
    @Test fun exactPreviousPathAndProfileAreRestoredForBothProfiles() {
        for (spec in BrainModelRegistry.all) {
            val previous = BrowserLeasePolicy.PhoneModel("/previous/custom/${spec.manifestId}", spec)
            assertSame(previous, BrowserLeasePolicy.restoration(previous, true, false))
            assertEquals(spec, BrowserLeasePolicy.browserProfile(previous, BrainModelRegistry.defaultProfile))
        }
    }
    @Test fun failedBrowserCleanupOrResidentPhonePreventsRestoration() {
        val previous = BrowserLeasePolicy.PhoneModel("/phone/model", BrainModelRegistry.GEMMA_4_E2B)
        assertNull(BrowserLeasePolicy.restoration(previous, false, false))
        assertNull(BrowserLeasePolicy.restoration(previous, true, true))
    }
    @Test fun noPreviousPhoneUsesSelectedProfileOrRegistryDefault() {
        for (spec in BrainModelRegistry.all) assertEquals(spec, BrowserLeasePolicy.browserProfile(null, spec))
        assertEquals(BrainModelRegistry.GEMMA_4_E2B, BrowserLeasePolicy.browserProfile(null, null))
    }
}
