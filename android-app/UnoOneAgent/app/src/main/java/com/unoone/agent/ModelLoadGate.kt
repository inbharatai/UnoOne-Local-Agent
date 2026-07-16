package com.unoone.agent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local single-flight gate for the native Gemma engine lifecycle.
 *
 * Application startup and Activity.onResume can happen back-to-back. Model readiness remains false
 * during the several-second native load, so checking only `isLlmLoaded()` lets both callers enqueue
 * a load. GemmaPlanner serializes those calls, but the second call then closes the freshly loaded
 * 2.5 GB engine and builds it again. This gate rejects the duplicate before it reaches LiteRT-LM.
 */
internal class ModelLoadGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }

    fun isInFlight(): Boolean = inFlight.get()
}
