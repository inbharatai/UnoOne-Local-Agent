package com.unoone.agent.core.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VoiceAdmissionTicketTest {
    @Test fun nativeDecodeReturningAfterStopCannotDispatchOrRetry() = runBlocking {
        val native = CompletableDeferred<String>()
        val captured = GlobalTaskCancellation.generation
        val decoded = async { VoiceAdmissionTicket(native.await(), captured) }
        GlobalTaskCancellation.cancelAll()
        native.complete("") // An unclear late native result must not start retry TTS.
        val ticket = decoded.await()
        var retries = 0
        var commands = 0
        if (ticket.isCurrent()) {
            if (ticket.text.isBlank()) retries++ else commands++
        }
        assertEquals(0, retries)
        assertEquals(0, commands)
        assertTrue(VoiceAdmissionTicket("fresh", GlobalTaskCancellation.generation).isCurrent())
    }

    @Test fun stopAfterDecodeInvalidatesBufferedQueueButNotFutureCapture() = runBlocking {
        val queue = Channel<VoiceAdmissionTicket>(16)
        val captured = GlobalTaskCancellation.generation
        val native = CompletableDeferred("old command")
        queue.send(VoiceAdmissionTicket(native.await(), captured))
        GlobalTaskCancellation.cancelAll()
        queue.send(VoiceAdmissionTicket("future command", GlobalTaskCancellation.generation))
        val accepted = mutableListOf<String>()
        repeat(2) { val ticket = queue.receive(); if (ticket.isCurrent()) accepted += ticket.text }
        assertEquals(listOf("future command"), accepted)
        queue.close()
        Unit
    }

    @Test fun capturedTicketIsNotRefreshedByAnotherResult() {
        val captured = GlobalTaskCancellation.generation
        GlobalTaskCancellation.cancelAll()
        val newer = VoiceAdmissionTicket("newer", GlobalTaskCancellation.generation)
        val late = VoiceAdmissionTicket("late", captured)
        assertTrue(newer.isCurrent())
        assertFalse(late.isCurrent())
    }
}
