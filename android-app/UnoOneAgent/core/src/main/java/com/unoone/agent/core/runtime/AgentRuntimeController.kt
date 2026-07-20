package com.unoone.agent.core.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle-independent control surface for UnoOne's persistent master enable state.
 *
 * Keeping the ViewModel dependent on this small contract makes an immediately emitted persisted
 * disabled state testable without constructing an Android [android.app.Application].
 */
interface AgentRuntimeController {
    val isAgentEnabled: StateFlow<Boolean>

    fun disableAgent()

    fun enableAgent()
}
