package com.unoone.agent.core.task

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BlindAidProducerGateTest {
    @Test fun stopReturnsWhileDecodeBlockedAndAllProducersMustAck() = runBlocking {
        val gate = BlindAidProducerGate()
        gate.deactivate(1); assertTrue(gate.open(1))
        val decode = CompletableDeferred<Unit>()
        var revoked = false
        val first = gate.register(1) { revoked = true }!!
        val second = gate.register(1) {}!!
        val analyzer = launch { decode.await(); first.acknowledgeClosed() }
        gate.deactivate(2) // This call returns synchronously while decode remains blocked.
        assertTrue(revoked)
        assertFalse(decode.isCompleted)
        var newModel = false
        val release = launch { gate.awaitAllClosed(); newModel = true }
        yield(); assertFalse(newModel)
        decode.complete(Unit); analyzer.join(); yield(); assertFalse(newModel)
        second.acknowledgeClosed(); release.join(); assertTrue(newModel)
    }

    @Test fun lateInitDisposeAndLateRegistrationAreRejected() = runBlocking {
        val gate = BlindAidProducerGate()
        gate.deactivate(7); assertTrue(gate.open(7))
        val init = CompletableDeferred<Unit>()
        val producer = gate.register(7) {}!!
        gate.deactivate(8)
        assertNull(gate.register(7) {})
        assertFalse(gate.open(7))
        val cleanup = launch { init.await(); producer.acknowledgeClosed() }
        val drain = async { gate.awaitAllClosed() }
        yield(); assertFalse(drain.isCompleted)
        init.complete(Unit); cleanup.join(); drain.await()
    }

    @Test fun rapidReactivationOldAckCannotClearNewOwner() = runBlocking {
        val gate = BlindAidProducerGate()
        gate.deactivate(1); gate.open(1)
        val old = gate.register(1) {}!!
        gate.deactivate(2)
        old.acknowledgeClosed(); gate.awaitAllClosed()
        gate.deactivate(3); assertTrue(gate.open(3))
        val current = gate.register(3) {}!!
        old.acknowledgeClosed()
        gate.deactivate(4)
        val drain = async { gate.awaitAllClosed() }
        yield(); assertFalse(drain.isCompleted)
        current.acknowledgeClosed(); drain.await()
    }

    @Test fun neverInstantiatedOnlyCurrentGenerationCanOpen() = runBlocking {
        val gate = BlindAidProducerGate()
        gate.deactivate(1); gate.open(1)
        gate.deactivate(2); gate.awaitAllClosed()
        gate.deactivate(3)
        assertFalse(gate.open(1)); assertFalse(gate.open(2)); assertTrue(gate.open(3))
    }
}
