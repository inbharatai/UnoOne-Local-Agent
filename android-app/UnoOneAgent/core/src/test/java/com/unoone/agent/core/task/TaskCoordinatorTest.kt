package com.unoone.agent.core.task

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TaskCoordinatorTest {
    private val kind = WorkerKind("native")
    private val scope = TaskScope(setOf(TaskCapability.LOCAL_READ, TaskCapability.MODEL))
    private fun request(key: String, generation: Long = 0, priority: TaskPriority = TaskPriority.NORMAL) =
        TaskRequest(RequestId(key), kind, "private-$key", scope, generation, priority = priority)
    private fun ok() = WorkerResult.Finished(TaskResult(TaskOutcome.RESPONDED))
    private fun id(a: Admission) = (a as Admission.Accepted).taskId

    @Test fun boundedActionKeepsItsOwnTerminalStateAndRecoveryLabel() = runBlocking {
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) {
            WorkerResult.Finished(TaskResult(TaskOutcome.ACTION_VERIFIED))
        }), this)
        val task = id(c.submit(request("action")))
        assertEquals(TaskOutcome.ACTION_VERIFIED, c.await(task).outcome)
        val summary = c.tasks.value.single { it.id == task }
        assertEquals(TaskState.ACTION_VERIFIED, summary.state)
        assertEquals(TaskState.ACTION_VERIFIED, TaskJournalEvent(sequence = 1, summary = summary).recoveredSummary().state)
        c.close()
    }

    @Test fun maintenanceDeniesConcurrentIntakeAndFailureReleasesReservation() = runBlocking {
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) { ok() }), this)
        try {
            val lease = c.beginMaintenance()!!
            try {
                assertNull(c.beginMaintenance())
                assertTrue(withContext(Dispatchers.Default) { c.submit(request("during")) } is Admission.Rejected)
                throw java.io.IOException("simulated metadata failure")
            } finally { lease.close() }
        } catch (_: java.io.IOException) { }
        val after = id(c.submit(request("after")))
        assertEquals(TaskOutcome.RESPONDED, c.await(after).outcome)
        c.close()
    }

    @Test fun maintenanceRejectsCancellingJobsUntilTheyActuallyDrain() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { release.await() } }
        }), this)
        val task = id(c.submit(request("draining")))
        entered.await(); c.cancel(task); yield()
        assertNull(c.beginMaintenance())
        release.complete(Unit); c.await(task); yield()
        val lease = c.beginMaintenance()!!
        lease.close(); c.close()
    }

    @Test fun maintenanceResourceOrderAndStopDoNotWaitForBlockedDisk() = runBlocking {
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) { ok() }), this)
        val ui = TaskResourceArbiter(false); val model = TaskResourceArbiter(true)
        val externalEntered = CompletableDeferred<Unit>(); val externalRelease = CompletableDeferred<Unit>()
        val external = launch {
            ui.withLease(TaskId("external-browser"), {}) {
                externalEntered.complete(Unit); externalRelease.await()
            }
        }
        externalEntered.await()
        val reservation = c.beginMaintenance()!!
        val enteredDisk = CompletableDeferred<Unit>(); val releaseDisk = CompletableDeferred<Unit>()
        val maintenance = launch {
            try {
                ui.withLease(TaskId("maintenance"), reservation::checkActive) {
                    model.withLease(TaskId("maintenance"), reservation::checkActive) {
                        enteredDisk.complete(Unit); releaseDisk.await()
                    }
                }
            } finally { reservation.close() }
        }
        yield(); assertFalse(enteredDisk.isCompleted); assertNull(model.owner())
        assertTrue(c.submit(request("blocked-admission")) is Admission.Rejected)
        externalRelease.complete(Unit); external.join(); enteredDisk.await()
        withTimeout(1000) { withContext(Dispatchers.Default) { c.cancelAll() } }
        try { reservation.checkActive(); fail("Stop must revoke maintenance without waiting for IO") }
        catch (_: CancellationException) { }
        assertTrue(c.submit(request("still-reserved", c.captureGeneration())) is Admission.Rejected)
        releaseDisk.complete(Unit); maintenance.join()
        assertNotNull(c.beginMaintenance()?.also { it.close() }); c.close()
    }

    @Test fun boundedQueueDedupePriorityFifoNoLoss() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) {
            if (it.instruction == "private-first") gate.await()
            seen.add(it.instruction); ok()
        }), this)
        val first = id(c.submit(request("first")))
        yield()
        val low = id(c.submit(request("low", priority = TaskPriority.MAINTENANCE)))
        val high = id(c.submit(request("high", priority = TaskPriority.INTERACTIVE)))
        val rest = (1..30).map { id(c.submit(request("q$it"))) }
        assertEquals(RejectionReason.QUEUE_FULL, (c.submit(request("overflow")) as Admission.Rejected).reason)
        assertTrue((c.submit(request("low")) as Admission.Accepted).duplicate)
        gate.complete(Unit)
        (listOf(first, low, high) + rest).forEach { c.await(it) }
        assertEquals(33, seen.size)
        assertEquals("private-high", seen[1])
        assertEquals((1..30).map { "private-q$it" }, seen.subList(2,32))
        assertEquals("private-low", seen.last())
        assertFalse(c.tasks.value.toString().contains("private"))
        c.close()
    }
    @Test fun twoTasksHaveIsolatedInstructionsAndCancellation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val contexts = mutableListOf<TaskContext>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.BACKGROUND) {
            contexts.add(it); gate.await(); it.beforeAction(TaskCapability.LOCAL_READ); ok()
        }), this)
        val a = id(c.submit(request("a"))); val b = id(c.submit(request("b")))
        yield(); assertEquals(2, contexts.size)
        c.cancel(a)
        assertEquals(TaskOutcome.CANCELLED, c.await(a).outcome)
        try { contexts.first { it.taskId == a }.beforeAction(TaskCapability.LOCAL_READ); fail() } catch (_: CancellationException) {}
        gate.complete(Unit)
        assertEquals(TaskOutcome.RESPONDED, c.await(b).outcome)
        assertEquals(setOf("private-a", "private-b"), contexts.map { it.instruction }.toSet())
        c.close()
    }
    @Test fun stopRevokesQueuedRunningAndOldEpoch() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        lateinit var context: TaskContext
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) { context = it; gate.await(); ok() }), this)
        val a = id(c.submit(request("a"))); val b = id(c.submit(request("b")))
        yield(); val stopped = c.cancelAll()
        assertEquals(setOf(a,b), stopped.cancelled)
        assertEquals(TaskOutcome.CANCELLED, c.await(b).outcome)
        assertEquals(RejectionReason.STALE_EPOCH, (c.submit(request("stale")) as Admission.Rejected).reason)
        try { context.beforeAction(TaskCapability.LOCAL_READ); fail() } catch (_: CancellationException) {}
        gate.complete(Unit); c.close()
    }
    @Test fun backgroundBoundIsTwo() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val count = AtomicInteger(); val peak = AtomicInteger()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.BACKGROUND) {
            val n = count.incrementAndGet(); peak.updateAndGet { old -> maxOf(old,n) }
            gate.await(); count.decrementAndGet(); ok()
        }), this)
        val ids = (1..6).map { id(c.submit(request("$it"))) }
        yield(); assertEquals(2, count.get()); gate.complete(Unit)
        ids.forEach { c.await(it) }; assertEquals(2, peak.get()); c.close()
    }
    @Test fun childrenCannotEscalateOrResetBudgetsAndDoNotDeadlock() = runBlocking {
        lateinit var c: TaskCoordinator
        c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) { ctx ->
            if (ctx.parentId == null) {
                assertEquals(RejectionReason.SCOPE_ESCALATION, (ctx.delegate(ChildRequest(RequestId("bad"), kind, "child", TaskScope(setOf(TaskCapability.UI_WRITE)))) as Admission.Rejected).reason)
                ctx.beforeAction(TaskCapability.LOCAL_READ)
                repeat(2) { assertTrue(ctx.delegate(ChildRequest(RequestId("child$it"),kind,"child",scope)) is Admission.Accepted) }
                assertEquals(RejectionReason.CHILD_LIMIT,(ctx.delegate(ChildRequest(RequestId("third"),kind,"child",scope)) as Admission.Rejected).reason)
                WorkerResult.WaitingForChildren
            } else {
                assertEquals(RejectionReason.DEPTH_LIMIT,(ctx.delegate(ChildRequest(RequestId("grandchild"),kind,"child",scope)) as Admission.Rejected).reason)
                ctx.beforeAction(TaskCapability.LOCAL_READ); ok()
            }
        }), this)
        val root = id(c.submit(request("root").copy(budget = TaskBudget(actions = 2))))
        assertEquals(TaskOutcome.FAILED, withTimeout(2000) { c.await(root) }.outcome)
        c.close()
    }
    @Test fun agingBeatsNewPriorityAndWallBudgetIsShared() = runBlocking {
        var time = 0L; val gate = CompletableDeferred<Unit>(); val seen = mutableListOf<String>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind,WorkerLane.INTERACTIVE) {
            if (it.instruction == "private-first") gate.await()
            seen.add(it.instruction); ok()
        }),this, { time }, 10)
        c.submit(request("first")); yield()
        val old = id(c.submit(request("old",priority=TaskPriority.MAINTENANCE)))
        time = 100
        val newer = id(c.submit(request("new",priority=TaskPriority.INTERACTIVE)))
        gate.complete(Unit); c.await(old); c.await(newer)
        assertEquals(listOf("private-first","private-old","private-new"),seen)
        c.close()
    }
    @Test fun resourceNestingOwnerCancellationAndOrder() = runBlocking {
        val ui = ProcessTaskResources.ui; val model = ProcessTaskResources.model
        val a = TaskId("a"); val b = TaskId("b")
        withTimeout(2000) {
            ui.withLease(a,{}) { lease ->
                ui.withLease(a,{}) { assertSame(lease,it) }
                model.withLease(a,{}) { model.withLease(a,{}) { assertEquals(a,it.owner) } }
                assertFalse(ui.cancelOwner(b))
            }
            model.withLease(a,{}) {
                try { ui.withLease(a,{}) {}; fail() } catch (_: IllegalStateException) {}
            }
        }
        assertNull(ui.owner()); assertNull(model.owner())
    }
    @Test fun competingUiOwnersNeverOverlapAndInheritedLeaseIsRejected() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val jobs = (1..6).map { n -> launch {
            ProcessTaskResources.ui.withLease(TaskId("ui$n"),{}) {
                val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it,count) }
                coroutineScope {
                    launch {
                        try { ProcessTaskResources.ui.withLease(TaskId("ui$n"),{}) {}; fail() }
                        catch (_: IllegalArgumentException) {}
                    }.join()
                }
                yield(); active.decrementAndGet()
            }
        } }
        jobs.joinAll(); assertEquals(1,peak.get())
    }
    @Test fun sharedModelAndWallBudgetAndTicketEpoch() = runBlocking {
        var time = 0L
        lateinit var root: TaskContext
        val gate = CompletableDeferred<Unit>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind,WorkerLane.INTERACTIVE) { ctx ->
            if (ctx.parentId == null) {
                root = ctx; ctx.beforeModelCall()
                ctx.delegate(ChildRequest(RequestId("child-model"),kind,"child",scope))
                WorkerResult.WaitingForChildren
            } else { gate.await(); ctx.beforeModelCall(); ok() }
        }),this,{time})
        val id = id(c.submit(request("model-root").copy(budget=TaskBudget(wallMillis=100,modelCalls=1))))
        yield()
        val ticket = TicketBinding(id,root.taskEpoch,root.stopGeneration,1,99)
        assertEquals(TicketDecision.VALID_RECHECK_REQUIRED,c.validateTicket(ticket))
        gate.complete(Unit)
        assertEquals(TaskOutcome.FAILED,c.await(id).outcome)
        assertEquals(TicketDecision.STALE,c.validateTicket(ticket))
        val expired = id(c.submit(request("expired").copy(budget=TaskBudget(wallMillis=1))))
        time = 5
        assertEquals(TaskOutcome.FAILED,c.await(expired).outcome)
        c.close()
    }
    @Test fun cancelParentRevokesChildAndKeepsUnrelatedTask() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = TaskCoordinator(listOf(WorkerRegistration(kind,WorkerLane.BACKGROUND) { ctx ->
            if (ctx.instruction == "private-root") {
                ctx.delegate(ChildRequest(RequestId("kid"),kind,"kid",scope))
                WorkerResult.WaitingForChildren
            } else { gate.await(); ctx.checkActive(); ok() }
        }),this)
        val root = id(c.submit(request("root"))); val other = id(c.submit(request("other")))
        yield(); yield()
        assertEquals(2,c.cancel(root).cancelled.size)
        assertEquals(TaskOutcome.CANCELLED,c.await(root).outcome)
        gate.complete(Unit); assertEquals(TaskOutcome.RESPONDED,c.await(other).outcome)
        c.close()
    }
    @Test fun journalRecoveryNeverReturnsExecutableState() {
        val event = TaskJournalEvent(sequence=1,summary=TaskSummary(TaskId("x"),null,TaskSource.TEXT,TaskState.RUNNING,1))
        assertEquals(TaskState.NEEDS_REVIEW,event.recoveredSummary().state)
    }
}
