package com.unoone.agent.core.model

import kotlinx.coroutines.sync.Mutex
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
object E4bRuntimeCoordinator {
    val operationMutex = Mutex()

    private val snapshotRef = AtomicReference(E4bRuntimeSnapshot())

    fun snapshot(): E4bRuntimeSnapshot = snapshotRef.get()

    fun transition(state: E4bRuntimeState, owner: String? = null, detail: String = "") {
        snapshotRef.set(E4bRuntimeSnapshot(state, owner, detail.take(160)))
    }
}
