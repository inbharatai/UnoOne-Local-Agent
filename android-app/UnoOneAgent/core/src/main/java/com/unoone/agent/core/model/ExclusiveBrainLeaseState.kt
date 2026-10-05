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

    suspend fun acquire(ownerId: String): Boolean = E4bRuntimeCoordinator.reserve(ownerId)
    suspend fun release(ownerId: String): Boolean = E4bRuntimeCoordinator.releaseReservation(ownerId)
    fun isActive(): Boolean = currentOwner() != null
    fun currentOwner(): String? = E4bRuntimeCoordinator.reservedOwner()
}
