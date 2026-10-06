package com.unoone.agent.core.overlay

import org.junit.Assert.*
import org.junit.Test

class OwnOverlayBrokerTest {
    private class FakeWindowPort : OwnOverlayBroker.WindowPort {
        val operations = mutableListOf<String>()
        var failRemove: String? = null
        override fun add(id: String) { operations += "add:$id" }
        override fun remove(id: String) { operations += "remove:$id"; check(id != failRemove) }
    }
    @Test fun noNotificationMeansZeroHideOperations() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1) { false }
        b.register("bubble", idle = true); b.register("chat")
        val before = p.operations.toList()
        assertTrue(runCatching { b.acquire("capture", 0) }.isFailure)
        assertEquals(before, p.operations)
    }
    @Test fun deniedChannelCannotHideEvenAfterAnEarlierAdmission() {
        var channelEnabled = true
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1) { channelEnabled }
        b.register("bubble")
        val first = b.acquire("capture", 0); b.release(first)
        channelEnabled = false
        val before = p.operations.toList()
        assertTrue(runCatching { b.acquire("next", 0) }.isFailure)
        assertEquals(before, p.operations)
    }
    @Test fun operationSequenceAndNesting() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1)
        b.register("bubble", idle = true); b.register("chat")
        val a = b.acquire("a", 0); val c = b.acquire("c", 0)
        b.register("panel") // Born hidden, never flashes into compositor.
        b.release(a); b.release(a)
        assertEquals(listOf("add:bubble", "add:chat", "remove:bubble", "remove:chat"), p.operations)
        b.desired("chat", false); b.release(c)
        assertEquals(listOf("add:bubble", "add:panel"), p.operations.takeLast(2))
    }
    @Test fun stopWhileHiddenAndLateAckCannotReopenChat() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1)
        b.register("bubble", idle = true); b.register("chat")
        val a = b.acquire("old", 0); b.stop(true)
        assertFalse(b.current(a)); val afterStop = p.operations.toList(); b.release(a)
        assertEquals(afterStop, p.operations); assertEquals("add:bubble", p.operations.last())
        assertEquals(1, p.operations.count { it == "add:chat" })
    }
    @Test fun disabledStopNeverRestores() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1)
        b.register("bubble", idle = true); val a = b.acquire("old", 0)
        b.stop(false); b.release(a)
        assertEquals(listOf("add:bubble", "remove:bubble"), p.operations)
    }
    @Test fun removeFailureFailsAckAndStillRemovesOthers() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1)
        b.register("bubble"); b.register("chat"); p.failRemove = "bubble"
        assertTrue(runCatching { b.acquire("a", 0) }.isFailure)
        assertEquals(listOf("remove:bubble", "remove:chat"), p.operations.takeLast(2))
        assertTrue(runCatching { b.acquire("retry", 0) }.isFailure)
    }
    @Test fun serviceDestroyAndForeignReceiptNeverReshow() {
        val p = FakeWindowPort(); val b = OwnOverlayBroker(p, 1)
        b.register("bubble"); val a = b.acquire("a", 0); b.destroy(); b.release(a)
        assertFalse(b.current(a)); assertEquals("remove:bubble", p.operations.last())
        val replacement = OwnOverlayBroker(p, 2); replacement.register("new")
        val c = replacement.acquire("c", 0); replacement.release(a)
        assertTrue(replacement.current(c)); assertEquals("remove:new", p.operations.last())
        assertTrue(runCatching { replacement.desired("unregistered", true) }.isFailure)
    }
}
