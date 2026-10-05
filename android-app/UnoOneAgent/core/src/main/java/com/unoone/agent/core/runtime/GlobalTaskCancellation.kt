package com.unoone.agent.core.runtime

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Process-wide task Stop, independent of whichever model currently owns the lease.
 * Callbacks must revoke native authority synchronously; UI cleanup may be dispatched afterwards.
 * Weak ownership and explicit close prevent a retained registry from keeping dead screens alive.
 */
object GlobalTaskCancellation {
    private class Entry(val invoke: () -> Unit) {
        val enabled = AtomicBoolean(true)
    }
    private val entries = CopyOnWriteArrayList<Entry>()
    private val epoch = AtomicLong()
    private val dispatching = ThreadLocal<Boolean>()
    val generation: Long get() = epoch.get()

    fun <T : Any> register(owner: T, cancel: (T) -> Unit): AutoCloseable {
        val reference = WeakReference(owner)
        val entry = Entry { reference.get()?.let(cancel) }
        entries.add(entry)
        return AutoCloseable {
            entry.enabled.set(false)
            entries.remove(entry)
        }
    }

    fun cancelAll() {
        if (dispatching.get() == true) return
        dispatching.set(true)
        try {
            epoch.incrementAndGet()
            // No registry lock is held while invoking callbacks. One failed owner cannot skip others.
            entries.forEach { if (it.enabled.get()) runCatching { it.invoke() } }
        } finally {
            dispatching.remove()
        }
    }
}
