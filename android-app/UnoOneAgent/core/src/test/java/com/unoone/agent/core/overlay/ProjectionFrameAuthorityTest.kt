package com.unoone.agent.core.overlay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ProjectionFrameAuthorityTest {
    @Test fun stopMidCaptureDiscardsAndRecyclesFrame() {
        val stop = AtomicLong()
        val authority = ProjectionFrameAuthority<Any> { stop.get() }
        val ticket = authority.install(Any())
        val frame = Any()
        stop.incrementAndGet()
        var recycled: Any? = null
        assertNull(authority.accept(ticket, frame) { recycled = it })
        assertSame(frame, recycled)
        assertFalse(authority.current(ticket))
    }
    @Test fun replacementRevokesOldFrameAndOldCallbackCannotClearNewOwner() {
        val authority = ProjectionFrameAuthority<Any> { 0L }
        val old = Any(); val next = Any()
        val oldTicket = authority.install(old)
        val nextTicket = authority.install(next)
        assertFalse(authority.clear(old))
        var recycled = false
        assertNull(authority.accept(oldTicket, Any()) { recycled = true })
        assertTrue(recycled)
        assertTrue(authority.current(nextTicket))
        assertSame(next, authority.snapshot()!!.owner)
    }
    @Test fun freshGrantAfterStopOwnsFramesAndLateOldClearIsHarmless() {
        var stop = 0L
        val authority = ProjectionFrameAuthority<Any> { stop }
        val old = Any(); authority.install(old)
        stop++
        val next = authority.install(Any())
        assertFalse(authority.clear(old))
        assertTrue(authority.current(next))
        val frame = Any()
        assertSame(frame, authority.accept(next, frame) { fail("Current frame recycled") })
    }
}
