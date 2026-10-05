package com.unoone.agent.browser

/** Local UI Stop and process-wide Stop both revoke callbacks, even non-cancellable native work. */
class BrowserAsyncOwnership(private val globalGeneration: () -> Long) {
    data class Ticket internal constructor(val local: Long, val global: Long)
    private var epoch = 0L
    private var closed = false
    @Synchronized fun capture(): Ticket = Ticket(epoch, globalGeneration())
    @Synchronized fun isCurrent(ticket: Ticket): Boolean =
        !closed && ticket.local == epoch && ticket.global == globalGeneration()
    /** Admission and revocation share a monitor; actions must be short and non-suspending. */
    @Synchronized fun runIfCurrent(ticket: Ticket, action: () -> Unit) {
        if (isCurrent(ticket)) action()
    }
    @Synchronized fun revoke(close: Boolean = false, cleanup: () -> Unit = {}) {
        epoch++
        closed = closed || close
        cleanup()
    }
}
