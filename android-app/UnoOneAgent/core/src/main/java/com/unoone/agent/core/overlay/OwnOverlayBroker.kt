package com.unoone.agent.core.overlay

/** Main-thread confined. ACK proves successful remove calls, never compositor absence. */
class OwnOverlayBroker(private val port: WindowPort, val serviceGeneration: Long, private val stopAvailable: () -> Boolean = { true }) {
    interface WindowPort { fun add(id: String); fun remove(id: String) }
    data class Receipt(val serviceGeneration: Long, val session: Long, val token: Long, val owner: String, val stopGeneration: Long)
    private data class Window(var desired: Boolean, var attached: Boolean = false, val idle: Boolean)
    private val windows = linkedMapOf<String, Window>()
    private val holders = linkedMapOf<Long, Receipt>()
    private var next = 0L
    private var session = 0L
    private var alive = true
    private var healthy = true
    fun register(id: String, desired: Boolean = true, idle: Boolean = false) {
        check(alive && !windows.containsKey(id)) { "Unknown/duplicate ownership" }
        windows[id] = Window(desired, idle = idle)
        reconcile()
    }
    fun desired(id: String, visible: Boolean) {
        check(alive); checkNotNull(windows[id]) { "Unregistered window" }.desired = visible
        reconcile()
    }
    fun acquire(owner: String, stopGeneration: Long): Receipt {
        check(alive && healthy)
        check(stopAvailable()) { "No available native Stop surface" }
        val receipt = Receipt(serviceGeneration, session, ++next, owner, stopGeneration)
        holders[receipt.token] = receipt
        try { reconcile() } catch (t: Throwable) { healthy = false; throw t }
        return receipt
    }
    fun current(r: Receipt): Boolean = alive && healthy && r.serviceGeneration == serviceGeneration && r.session == session && holders[r.token] == r
    fun release(r: Receipt) {
        if (!current(r)) return
        holders.remove(r.token)
        reconcile()
    }
    /** Does not enable master or restart work. Only an already requested idle affordance survives Stop. */
    fun stop(masterEnabled: Boolean) {
        session++; holders.clear()
        windows.values.forEach { it.desired = it.desired && it.idle && masterEnabled }
        reconcile()
    }
    fun destroy() {
        alive = false; session++; holders.clear()
        windows.values.forEach { it.desired = false }
        reconcile()
    }
    private fun reconcile() {
        var failure: Throwable? = null
        windows.forEach { (id, window) ->
            val show = alive && healthy && holders.isEmpty() && window.desired
            try {
                if (!show && window.attached) { port.remove(id); window.attached = false }
                if (show && !window.attached) { port.add(id); window.attached = true }
            } catch (t: Throwable) { healthy = false; if (failure == null) failure = t }
        }
        failure?.let { throw it }
    }
}
