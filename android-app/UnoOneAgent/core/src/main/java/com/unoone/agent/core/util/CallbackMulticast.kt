package com.unoone.agent.core.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe multicast callback that supports multiple listeners.
 *
 * Unlike a single-delegate `var callback: ((Args) -> Unit)?` which gets overwritten
 * when a second listener registers, CallbackMulticast distributes events to ALL
 * registered listeners. This is essential because both MainActivity and
 * FloatingAgentService need to receive permission/confirmation prompts.
 *
 * Listeners are stored in a CopyOnWriteArrayList for thread-safe iteration
 * without external synchronization.
 */
class CallbackMulticast<T> {

    private val listeners = CopyOnWriteArrayList<T>()

    /** Register a listener. Returns true if added (was not already present). */
    fun add(listener: T): Boolean {
        val added = listeners.add(listener)
        if (added) Logger.d("CallbackMulticast: listener added (total=${listeners.size})")
        return added
    }

    /** Unregister a listener. Returns true if it was present and removed. */
    fun remove(listener: T): Boolean {
        val removed = listeners.remove(listener)
        if (removed) Logger.d("CallbackMulticast: listener removed (total=${listeners.size})")
        return removed
    }

    /** Remove all listeners. */
    fun clear() {
        listeners.clear()
    }

    /** Number of registered listeners. */
    val size: Int get() = listeners.size

    /** Whether any listeners are registered. */
    val hasListeners: Boolean get() = listeners.isNotEmpty()

    /**
     * Invoke a function on all registered listeners.
     * Catches and logs exceptions from individual listeners so one failing
     * listener doesn't prevent others from being notified.
     */
    fun invokeAll(action: (T) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (e: Exception) {
                Logger.e("CallbackMulticast: listener threw exception", e)
            }
        }
    }
}

/**
 * Convenience type for the confirmation callback pattern used by AgentOrchestrator.
 * Each listener receives (message, callback) and can show UI and invoke the callback
 * with the user's response.
 */
typealias ConfirmationListener = (message: String, callback: (Boolean) -> Unit) -> Unit

/**
 * Convenience type for the permission callback pattern used by AgentOrchestrator.
 * Each listener receives the list of missing permissions.
 */
typealias PermissionListener = (List<String>) -> Unit

/**
 * Convenience type for the system-permission callback pattern used by AgentOrchestrator.
 * Each listener receives the list of unsatisfied non-runtime requirements
 * (Accessibility / Overlay / MediaProjection).
 */
typealias SystemPermissionListener = (List<com.unoone.agent.core.safety.PermissionRequirement>) -> Unit