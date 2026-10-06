package com.unoone.agent.core.overlay

import java.util.concurrent.atomic.AtomicReference

/** Revocation never waits for capture IO. Tickets use identity, not reusable projection equality. */
class ProjectionFrameAuthority<T : Any>(private val stopGeneration: () -> Long) {
    class Ticket<T>(val owner: T, val stopGeneration: Long)
    private val active = AtomicReference<Ticket<T>?>(null)
    fun install(owner: T): Ticket<T> = Ticket(owner, stopGeneration()).also { active.set(it) }
    fun snapshot(): Ticket<T>? = active.get()
    fun current(ticket: Ticket<T>): Boolean = active.get() === ticket && ticket.stopGeneration == stopGeneration()
    fun clear(owner: T): Boolean {
        val ticket = active.get() ?: return false
        return ticket.owner === owner && active.compareAndSet(ticket, null)
    }
    /** Call immediately before returning an owned frame; rejected frames are destroyed. */
    fun <F> accept(ticket: Ticket<T>, frame: F, recycle: (F) -> Unit): F? =
        if (current(ticket)) frame else { recycle(frame); null }
}
