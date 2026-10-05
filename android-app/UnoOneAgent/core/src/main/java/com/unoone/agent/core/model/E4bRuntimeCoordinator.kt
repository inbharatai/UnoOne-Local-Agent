package com.unoone.agent.core.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Observable process state for the single Gemma 4 E4B native allocation. */
enum class E4bRuntimeState {
    UNLOADED,
    VERIFYING,
    LOADING_PHONE,
    PHONE_READY,
    PHONE_INFERENCING,
    TRANSITION_TO_BROWSER,
    LOADING_BROWSER,
    BROWSER_READY,
    BROWSER_INFERENCING,
    TRANSITION_TO_PHONE,
    UNLOADING,
    FAILED,
    DISABLED
}
data class E4bRuntimeSnapshot(
    val state: E4bRuntimeState = E4bRuntimeState.UNLOADED,
    val owner: String? = null,
    val detail: String = ""
)

/**
 * One process-wide serialization point for E4B verification, native load, inference and close.
 *
 * LiteRT-LM conversations support native cancellation, but closing a conversation or engine while
 * a callback is still inside native generation is unsafe. Both phone and browser planners hold
 * [operationMutex] for the complete native operation. A stop path may call `cancelProcess()`
 * concurrently, then waits for this mutex before closing native objects.
 */
object E4bRuntimeCoordinator : E4bRuntimeGate() {
    const val PHONE_OWNER = "phone-agent"
}

/** Isolated gate instances permit deterministic native-lifecycle tests without resetting quarantine. */
open class E4bRuntimeGate internal constructor() {
    val operationMutex = Mutex()

    private val phoneOwner = E4bRuntimeCoordinator.PHONE_OWNER
    @Volatile private var reservation: String? = null
    private var allocation: Any? = null
    private var allocationOwner: String? = null
    private var quarantined = false

    fun reservedOwner(): String? = reservation

    suspend fun reserve(owner: String): Boolean = operationMutex.withLock {
        if (reservation != null || quarantined) false else {
            reservation = owner
            true
        }
    }

    suspend fun releaseReservation(owner: String): Boolean = operationMutex.withLock {
        if (reservation != owner || quarantined ||
            (allocation != null && allocationOwner != phoneOwner)) false
        else { reservation = null; true }
    }

    /** Refuse unauthorized replacement BEFORE touching this holder's resident native engine. */
    fun canReplaceAllocation(holder: Any, ownerToken: String): Boolean {
        check(operationMutex.isLocked)
        return !quarantined && (allocation == null || allocation === holder) &&
            (reservation?.let { it == ownerToken } ?: (ownerToken == phoneOwner))
    }

    /** Call only inside operationMutex, immediately before native construction. */
    fun claimAllocation(holder: Any, ownerToken: String, residentOwner: String): Boolean {
        check(operationMutex.isLocked)
        if (quarantined || allocation != null) return false
        if (reservation?.let { it != ownerToken } ?: (ownerToken != phoneOwner)) return false
        allocation = holder
        allocationOwner = residentOwner
        return true
    }

    /** Only after every native close returned successfully (or no allocation was constructed). */
    fun acknowledgeClosed(holder: Any) {
        check(operationMutex.isLocked)
        if (allocation === holder && !quarantined) {
            allocation = null
            allocationOwner = null
        }
    }

    fun quarantine(holder: Any) {
        check(operationMutex.isLocked)
        if (allocation === holder) quarantined = true
    }

    private val snapshotRef = AtomicReference(E4bRuntimeSnapshot())

    fun snapshot(): E4bRuntimeSnapshot = snapshotRef.get()

    fun transition(state: E4bRuntimeState, owner: String? = null, detail: String = "") {
        snapshotRef.set(E4bRuntimeSnapshot(state, owner, detail.take(160)))
    }
}
