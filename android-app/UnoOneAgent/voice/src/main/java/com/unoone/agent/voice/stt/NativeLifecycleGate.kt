package com.unoone.agent.voice.stt

/**
 * Owns a native resource without holding the state monitor across JNI. Invalidate is Main-safe;
 * initialize/use/close may block in native code and MUST run on a worker. A canceled constructor
 * cannot publish after invalidation: its local candidate is disposed before initialize returns.
 * A new service owns a new gate, so old cleanup can never close its replacement's resource.
 */
internal class NativeLifecycleGate<T : Any>(private val dispose: (T) -> Unit) {
    private val state = Any()
    private val operations = Any()
    private var generation = 0L
    private var closed = false
    private var resource: T? = null

    fun isOpen(): Boolean = synchronized(state) { !closed }
    fun hasResource(): Boolean = synchronized(state) { !closed && resource != null }

    fun initialize(factory: () -> T?): Boolean = synchronized(operations) {
        val ticket = synchronized(state) {
            if (closed) return false
            if (resource != null) return true
            generation
        }
        val candidate = factory() ?: return false
        val published = synchronized(state) {
            if (closed || generation != ticket) false
            else { resource = candidate; true }
        }
        if (!published) dispose(candidate)
        published
    }

    fun <R> use(block: (T) -> R): R? = synchronized(operations) {
        val current = synchronized(state) { if (closed) null else resource } ?: return null
        block(current)
    }

    /** Never waits for a constructor or decode. */
    fun invalidate() = synchronized(state) {
        if (!closed) { closed = true; generation++ }
    }

    /** Worker-only: waits for native acknowledgement before disposing, exactly once. */
    fun close() {
        invalidate()
        synchronized(operations) {
            val old = synchronized(state) { resource.also { resource = null } }
            if (old != null) dispose(old)
        }
    }
}
