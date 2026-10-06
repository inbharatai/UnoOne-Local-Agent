package com.unoone.agent.voice

import java.util.concurrent.atomic.AtomicReference

/** A pause is a capability, not a boolean: stale cleanup cannot resume a successor. */
class PassiveCaptureHandoff {
    private val owner = AtomicReference<Any?>(null)
    fun claim(token: Any): Boolean = owner.compareAndSet(null, token)
    fun paused(): Boolean = owner.get() != null
    fun owns(token: Any): Boolean = owner.get() === token
    fun release(token: Any): Boolean = owner.compareAndSet(token, null)
    suspend fun acknowledge(token: Any, halt: suspend () -> Boolean): Boolean =
        owns(token) && halt() && owns(token)
}

/** Enqueued speech retains the Stop epoch; finally never resets successor accounting. */
class QueuedSpeechGeneration {
    private val epoch = java.util.concurrent.atomic.AtomicLong(0)
    fun capture(): Long = epoch.get()
    fun current(token: Long): Boolean = token == epoch.get()
    fun stop() { epoch.incrementAndGet() }
}
