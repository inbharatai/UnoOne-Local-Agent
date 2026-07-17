package com.unoone.agent.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide, dependency-light master gate checked at every execution boundary.
 *
 * [UnoOneApplication] initializes this from durable preferences before it starts voice, model, or
 * automation services. It intentionally contains no Android dependency so lower-level modules can
 * fail fast without reaching the app layer.
 */
object AgentRuntimeGate {
    private val enabled = AtomicBoolean(true)

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }
}
