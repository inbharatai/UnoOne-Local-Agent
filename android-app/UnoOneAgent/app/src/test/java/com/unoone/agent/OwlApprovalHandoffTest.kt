package com.unoone.agent

import com.unoone.agent.owl.*
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.task.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwlApprovalHandoffTest {
    private fun consent() = OwlTaskConsent("com.unoone.agent", "device: click Search in com.unoone.agent", 4, 180)

    @Test fun stopDuringEachDelayNeverStartsModel() = runBlocking {
        for (stopAt in 1..4) {
            val approval = consent()
            var waits = 0; var launches = 0; var starts = 0; var intents = 0
            try {
                runApprovedOwlStart(true, approval::checkApproval, { intents++ }, { launches++ },
                    pause = { if (++waits == stopAt) GlobalTaskCancellation.cancelAll() }) { starts++ }
                fail("Revoked approval ran")
            } catch (_: CancellationException) { }
            assertEquals(0, starts)
            assertEquals(if (stopAt == 4) 1 else 0, launches)
            assertEquals(launches, intents)
        }
    }

    @Test fun pendingReviewCannotBeRebased() = runBlocking {
        val approval = consent()
        GlobalTaskCancellation.cancelAll()
        var effects = 0
        try {
            runApprovedOwlStart(true, approval.copy()::checkApproval, { effects++ }, { effects++ },
                pause = { effects++ }) { effects++ }
            fail("Stale review accepted")
        } catch (_: CancellationException) { }
        assertEquals(0, effects)
    }

    @Test fun queuedApprovalCannotStealUiAndStopPreventsLaunch() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val occupied = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            ProcessTaskResources.ui.withLease(TaskId("holder"), {}) {
                occupied.complete(Unit); release.await()
            }
        }
        occupied.await()
        val approval = consent()
        var effects = 0
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            ProcessTaskResources.ui.withLease(TaskId("owl"), approval::checkApproval) {
                runApprovedOwlStart(true, approval::checkApproval, { effects++ }, { effects++ },
                    pause = {}) { effects++ }
            }
        }
        assertEquals(TaskId("holder"), ProcessTaskResources.ui.owner())
        assertEquals(0, effects)
        GlobalTaskCancellation.cancelAll()
        release.complete(Unit)
        holder.join(); queued.join()
        assertEquals(0, effects)
        assertNull(ProcessTaskResources.ui.owner())
    }

    @Test fun revokedPrerequisiteDuringDelayPreventsNavigationAndModel() = runBlocking {
        val approval = consent()
        var prerequisite = true
        var effects = 0
        try {
            runApprovedOwlStart(true, { approval.checkApproval(); check(prerequisite) },
                { effects++ }, { effects++ }, pause = { prerequisite = false }) { effects++ }
            fail("Missing prerequisite was ignored")
        } catch (_: IllegalStateException) { }
        assertEquals(0, effects)
    }

    @Test fun admittedPracticeStartsExactlyOnceUnderItsLease() = runBlocking {
        val approval = consent(); val id = TaskId("normal-owl")
        val events = mutableListOf<String>()
        ProcessTaskResources.ui.withLease(id, approval::checkApproval) {
            fun check() { approval.checkApproval(); assertEquals(id, ProcessTaskResources.ui.owner()) }
            runApprovedOwlStart(true, ::check, { events += "intent" }, { events += "launch" },
                pause = { events += "wait:$it" }) { events += "start" }
        }
        assertEquals(listOf("wait:1000", "wait:1000", "wait:1000", "intent", "launch", "wait:1200", "start"), events)
    }
}
