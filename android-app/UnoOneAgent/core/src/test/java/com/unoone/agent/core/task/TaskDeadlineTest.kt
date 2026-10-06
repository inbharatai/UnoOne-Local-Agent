package com.unoone.agent.core.task

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TaskDeadlineTest {
    @Test fun deadlineRevokesQueuedAndRetainsRunningUntilCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val kind = WorkerKind("deadline")
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) {
            entered.complete(Unit)
            withContext(NonCancellable) { drain.await() }
            WorkerResult.Finished(TaskResult(TaskOutcome.RESPONDED))
        }), this)
        fun submit(key: String, wall: Long) = (c.submit(TaskRequest(RequestId(key), kind, "", TaskScope(), 0,
            budget = TaskBudget(wallMillis = wall))) as Admission.Accepted).taskId
        val running = submit("running", 60)
        entered.await()
        val queued = submit("queued", 20)
        withTimeout(2000) {
            while (c.tasks.value.first { it.id == running }.state != TaskState.CANCELLING) delay(5)
        }
        assertEquals(TaskState.CANCELLED, c.tasks.value.first { it.id == queued }.state)
        val next = submit("next", 2000)
        assertEquals(TaskState.QUEUED, c.tasks.value.first { it.id == next }.state)
        drain.complete(Unit)
        assertEquals(TaskOutcome.CANCELLED, c.await(running).outcome)
        c.cancel(next); c.close()
    }

    @Test fun revokedTaskCannotPublishNativeOutput() = runBlocking {
        val entered = CompletableDeferred<TaskContext>()
        val drain = CompletableDeferred<Unit>()
        val kind = WorkerKind("publish")
        var published = false
        val c = TaskCoordinator(listOf(WorkerRegistration(kind, WorkerLane.INTERACTIVE) { ctx ->
            entered.complete(ctx)
            withContext(NonCancellable) { drain.await() }
            ctx.complete(TaskResult(TaskOutcome.RESPONDED)) { published = true }
            WorkerResult.Finished(TaskResult(TaskOutcome.RESPONDED))
        }), this)
        val id = (c.submit(TaskRequest(RequestId("race"), kind, "", TaskScope(), 0)) as Admission.Accepted).taskId
        entered.await(); c.cancel(id); drain.complete(Unit)
        assertEquals(TaskOutcome.CANCELLED, c.await(id).outcome)
        assertFalse(published); c.close()
    }
}
