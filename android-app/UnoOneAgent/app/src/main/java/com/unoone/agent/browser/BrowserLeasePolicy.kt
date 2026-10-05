package com.unoone.agent.browser

import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.BrainModelSpec

/** Pure transition decisions. Lease occupancy is deliberately not physical residency. */
internal object BrowserLeasePolicy {
    data class PhoneModel(val path: String, val spec: BrainModelSpec)

    fun canAcquire(alreadyLeased: Boolean): Boolean = !alreadyLeased
    fun browserProfile(previous: PhoneModel?, selected: BrainModelSpec?): BrainModelSpec =
        previous?.spec ?: selected ?: BrainModelRegistry.defaultProfile
    fun canLoadBrowser(unloadAcknowledged: Boolean, phoneResident: Boolean): Boolean =
        unloadAcknowledged && !phoneResident
    fun restoration(previous: PhoneModel?, browserClosed: Boolean, phoneResident: Boolean): PhoneModel? =
        previous.takeIf { browserClosed && !phoneResident }
}
