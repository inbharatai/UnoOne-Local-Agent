package com.unoone.agent.core.model

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide ownership marker for the single on-device Gemma allocation.
 *
 * The normal phone planner and Secure Browser are mutually exclusive owners. While an external owner
 * holds the model, the phone parser must not treat its intentionally-unloaded conversation as a
 * failure and invoke self-heal, which would allocate a second multi-gigabyte engine.
 */
object ExclusiveBrainLeaseState {

    private val owner = AtomicReference<String?>(null)

    fun acquire(ownerId: String): Boolean = owner.compareAndSet(null, ownerId)

    fun release(ownerId: String): Boolean = owner.compareAndSet(ownerId, null)

    fun isActive(): Boolean = owner.get() != null

    fun currentOwner(): String? = owner.get()
}
