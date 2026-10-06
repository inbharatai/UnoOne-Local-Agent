package com.unoone.agent.core.task

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class WorkerExecution : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerExecution>
}

/** In-memory bounded native scheduler. No model-selected functions or executable journal. */
class TaskCoordinator(
    registrations: List<WorkerRegistration>,
    private val executionScope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val agingMillis: Long = 5_000
) : AutoCloseable {
    companion object {
        private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "task-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    private val lock = Any()
    private val workers = registrations.associateBy { it.kind }
    private val records = LinkedHashMap<TaskId, Record>()
    private val requests = HashMap<RequestId, TaskId>()
    private val queue = ArrayList<Record>()
    private var generation = 0L
    private var sequence = 0L
    private var order = 0L
    private var interactive = 0
    private var background = 0
    private var closed = false
    private var maintenance: MaintenanceLease? = null
    private val mutableTasks = MutableStateFlow<List<TaskSummary>>(emptyList())
    val tasks: StateFlow<List<TaskSummary>> = mutableTasks.asStateFlow()
    private val journal = ArrayDeque<TaskJournalEvent>()
    init { require(workers.size == registrations.size && agingMillis > 0) }
    private class Budget(val deadline: Long, var actions: Int, var models: Int)
    private class Record(val id: TaskId, var request: TaskRequest, val parent: TaskId?, val depth: Int,
        val budget: Budget, val admitted: Long, val order: Long, val generation: Long) {
        var epoch = 0L
        var revoked = false
        var state = TaskState.QUEUED
        var result: TaskResult? = null
        var seq = 0L
        var job: Job? = null
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        var cancellationReason = TaskReason.STOPPED
        val children = ArrayList<TaskId>()
        val completion = CompletableDeferred<TaskResult>()
    }
    /** Reserves intake only; callers do IO/native waits outside the coordinator monitor. */
    fun beginMaintenance(): MaintenanceLease? = synchronized(lock) {
        if (closed || maintenance != null || !maintenanceIdle()) null
        else MaintenanceLease(generation).also { maintenance = it }
    }
    private fun maintenanceIdle(): Boolean = queue.isEmpty() && interactive == 0 && background == 0 &&
        records.values.all { it.result != null && it.job?.isCompleted != false }

    inner class MaintenanceLease internal constructor(private val epoch: Long) : AutoCloseable {
        fun checkActive() = synchronized(lock) {
            if (closed || maintenance !== this || generation != epoch || !maintenanceIdle())
                throw CancellationException("Maintenance revoked or coordinator busy")
        }
        override fun close() { synchronized(lock) { if (maintenance === this) maintenance = null } }
    }
    fun captureGeneration(): Long = synchronized(lock) { generation }
    fun journalSnapshot(): List<TaskJournalEvent> = synchronized(lock) { journal.toList() }
    fun submit(request: TaskRequest): Admission = synchronized(lock) { admit(request, null) }
    private fun admit(input: TaskRequest, parent: Record?): Admission {
        if (closed || maintenance != null) return Admission.Rejected(RejectionReason.CLOSED)
        if (input.capturedStopGeneration != generation) return Admission.Rejected(RejectionReason.STALE_EPOCH)
        requests[input.requestId]?.let { id ->
            val old = records.getValue(id)
            // A request key cannot be reused to attach a sibling/root to another parent.
            if (old.parent != parent?.id) return Admission.Rejected(RejectionReason.INVALID_REQUEST)
            return Admission.Accepted(id, old.state, true)
        }
        if (input.worker !in workers) return Admission.Rejected(RejectionReason.UNKNOWN_WORKER)
        if (input.instruction.length > 32768 || input.requestId.value.length !in 1..128)
            return Admission.Rejected(RejectionReason.INVALID_REQUEST)
        if (queue.size >= 32) return Admission.Rejected(RejectionReason.QUEUE_FULL)
        // Evict only completed, detached families; dedupe is bounded to retained history.
        if (records.size >= 256) {
            val removable = records.values.firstOrNull { old ->
                old.result != null && old.job?.isCompleted != false &&
                    (old.parent == null || old.parent !in records || records[old.parent]?.result != null) &&
                    old.children.all { it !in records || records[it]?.result != null }
            }
            if (removable != null) {
                records.remove(removable.id)
                requests.remove(removable.request.requestId)
            } else return Admission.Rejected(RejectionReason.QUEUE_FULL)
        }
        val request = input.copy(scope = input.scope.frozen())
        val now = nowMillis()
        val r = Record(TaskId(UUID.randomUUID().toString()), request, parent?.id, (parent?.depth ?: -1) + 1,
            parent?.budget ?: Budget(now + request.budget.wallMillis, request.budget.actions, request.budget.modelCalls),
            now, order++, generation)
        records[r.id] = r
        requests[request.requestId] = r.id
        parent?.children?.add(r.id)
        queue.add(r)
        publish(r)
        r.watchdog = deadlines.schedule({
            synchronized(lock) {
                if (r.result == null && !r.revoked) {
                    records.values.filter { it.budget === r.budget }.toList()
                        .forEach { revokeTree(it, TaskReason.BUDGET_EXHAUSTED) }
                    pump()
                }
            }
        }, (r.budget.deadline - nowMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
        pump()
        return Admission.Accepted(r.id, r.state)
    }
    internal fun delegate(id: TaskId, child: ChildRequest): Admission = synchronized(lock) {
        if (maintenance != null) return@synchronized Admission.Rejected(RejectionReason.CLOSED)
        val p = records[id] ?: return@synchronized Admission.Rejected(RejectionReason.PARENT_NOT_RUNNING)
        checkLive(p)
        if (p.state != TaskState.RUNNING) return@synchronized Admission.Rejected(RejectionReason.PARENT_NOT_RUNNING)
        if (p.depth >= 1) return@synchronized Admission.Rejected(RejectionReason.DEPTH_LIMIT)
        if (!child.scope.isSubsetOf(p.request.scope)) return@synchronized Admission.Rejected(RejectionReason.SCOPE_ESCALATION)
        if (requests[child.requestId] == null && p.children.size >= 2) return@synchronized Admission.Rejected(RejectionReason.CHILD_LIMIT)
        admit(TaskRequest(child.requestId, child.worker, child.instruction, child.scope, p.generation,
            TaskSource.NATIVE, p.request.priority, p.request.budget), p)
    }
    private fun pump() {
        if (closed) return
        while (true) {
            val now = nowMillis()
            val next = queue.filter {
                it.generation == generation && !it.revoked && when (workers.getValue(it.request.worker).lane) {
                    WorkerLane.INTERACTIVE -> interactive < 1
                    WorkerLane.BACKGROUND -> background < 2
                }
            }.maxWithOrNull(compareBy<Record> { it.request.priority.rank.toLong() + (now - it.admitted).coerceAtLeast(0) / agingMillis }
                .thenBy { -it.order }) ?: return
            queue.remove(next)
            val registration = workers.getValue(next.request.worker)
            if (registration.lane == WorkerLane.INTERACTIVE) interactive++ else background++
            next.state = TaskState.RUNNING
            publish(next)
            val job = executionScope.launch(WorkerExecution(), start = CoroutineStart.LAZY) {
                val result = try {
                    checkActive(next.id)
                    registration.worker.run(TaskContext(this@TaskCoordinator, next.id, next.parent,
                        next.request.instruction, next.request.scope, next.epoch, next.generation, next.request.requestId)).also { checkActive(next.id) }
                } catch (_: CancellationException) {
                    WorkerResult.Finished(TaskResult(TaskOutcome.CANCELLED, TaskReason.STOPPED))
                } catch (_: TaskBudgetExceeded) {
                    WorkerResult.Finished(TaskResult(TaskOutcome.FAILED, TaskReason.BUDGET_EXHAUSTED))
                } catch (_: Exception) {
                    WorkerResult.Finished(TaskResult(TaskOutcome.FAILED, TaskReason.WORKER_FAILED))
                }
                synchronized(lock) {
                    if (!next.revoked && next.result == null) when (result) {
                        is WorkerResult.Finished -> {
                            // Unjoined children cannot outlive a completed parent.
                            next.children.mapNotNull(records::get).filter { it.result == null }.forEach { revokeTree(it) }
                            finish(next, result.result)
                        }
                        WorkerResult.WaitingForChildren -> {
                            next.state = TaskState.WAITING_CHILD
                            publish(next)
                            aggregate(next)
                        }
                    }
                }
            }
            next.job = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (registration.lane == WorkerLane.INTERACTIVE) interactive-- else background--
                    if (next.result == null && next.state in setOf(TaskState.RUNNING, TaskState.CANCELLING))
                        finish(next, TaskResult(TaskOutcome.CANCELLED, next.cancellationReason))
                    pump()
                }
            }
            job.start()
        }
    }
    private fun aggregate(parent: Record) {
        if (parent.state != TaskState.WAITING_CHILD || parent.result != null) return
        val children = parent.children.mapNotNull(records::get)
        if (children.any { it.result == null }) return
        val outcomes = children.map { it.result!!.outcome }
        val outcome = when {
            children.isEmpty() -> TaskOutcome.FAILED
            TaskOutcome.CANCELLED in outcomes -> TaskOutcome.CANCELLED
            TaskOutcome.FAILED in outcomes -> TaskOutcome.FAILED
            TaskOutcome.NEEDS_USER in outcomes -> TaskOutcome.NEEDS_USER
            TaskOutcome.UNVERIFIED in outcomes -> TaskOutcome.UNVERIFIED
            outcomes.all { it == TaskOutcome.VERIFIED } -> TaskOutcome.VERIFIED
            else -> TaskOutcome.RESPONDED
        }
        finish(parent, TaskResult(outcome, if (outcome == TaskOutcome.FAILED) TaskReason.CHILD_FAILED else TaskReason.NONE))
    }
    private fun finish(r: Record, result: TaskResult) {
        if (r.result != null) return
        r.watchdog?.cancel(false)
        r.result = result
        r.request = r.request.copy(instruction = "")
        r.state = when (result.outcome) {
            TaskOutcome.VERIFIED, TaskOutcome.RESPONDED -> TaskState.SUCCEEDED
            TaskOutcome.UNVERIFIED -> TaskState.UNVERIFIED
            TaskOutcome.NEEDS_USER -> TaskState.NEEDS_USER
            TaskOutcome.FAILED -> TaskState.FAILED
            TaskOutcome.CANCELLED -> TaskState.CANCELLED
        }
        r.completion.complete(result)
        publish(r)
        r.parent?.let { records[it]?.let(::aggregate) }
    }
    private fun publish(r: Record, receipt: StepReceipt? = null) {
        r.seq = ++sequence
        val summary = summary(r)
        journal.addLast(TaskJournalEvent(sequence = sequence, summary = summary, receipt = receipt))
        while (journal.size > 512) journal.removeFirst()
        mutableTasks.value = records.values.toList().takeLast(256).map(::summary)
    }
    private fun summary(r: Record) = TaskSummary(r.id, r.parent, r.request.source, r.state, r.seq, r.result?.outcome, r.request.worker, r.request.priority)
    private fun checkLive(r: Record) {
        if (closed || r.revoked || r.generation != generation || r.result != null) throw CancellationException("Task revoked")
        if (nowMillis() >= r.budget.deadline) throw TaskBudgetExceeded()
    }
    /** Native publication and terminal acceptance share cancellation's linearization lock. */
    internal fun complete(id: TaskId, result: TaskResult, publishOutput: () -> Unit) = synchronized(lock) {
        val r = records.getValue(id)
        checkLive(r)
        publishOutput()
        r.children.mapNotNull(records::get).filter { it.result == null }.forEach { revokeTree(it) }
        finish(r, result)
    }
    internal fun checkActive(id: TaskId) = synchronized(lock) { checkLive(records.getValue(id)) }
    internal fun consume(id: TaskId, capability: TaskCapability, model: Boolean) = synchronized(lock) {
        val r = records.getValue(id)
        checkLive(r)
        require(capability in r.request.scope.capabilities) { "Capability outside task scope" }
        if (model) {
            require(capability == TaskCapability.MODEL)
            if (r.budget.models <= 0) throw TaskBudgetExceeded()
            r.budget.models--
        } else {
            if (r.budget.actions <= 0) throw TaskBudgetExceeded()
            r.budget.actions--
        }
    }
    internal fun emit(receipt: StepReceipt) = synchronized(lock) {
        val r = records.getValue(receipt.taskId)
        checkLive(r)
        require(receipt.capability in r.request.scope.capabilities)
        publish(r, receipt)
    }
    suspend fun await(id: TaskId): TaskResult {
        check(currentCoroutineContext()[WorkerExecution] == null) {
            "Workers must return WaitingForChildren, not await while occupying a lane"
        }
        return synchronized(lock) { records.getValue(id).completion }.await()
    }
    fun validateTicket(binding: TicketBinding): TicketDecision = synchronized(lock) {
        val r = records[binding.taskId]
        if (r == null || r.revoked || r.result != null || r.epoch != binding.taskEpoch || generation != binding.stopGeneration ||
            nowMillis() >= binding.expiresAtMillis || nowMillis() >= r.budget.deadline) TicketDecision.STALE
        else TicketDecision.VALID_RECHECK_REQUIRED
    }
    private fun revokeTree(r: Record, reason: TaskReason = TaskReason.STOPPED): Set<TaskId> {
        if (r.result != null || r.revoked) return emptySet()
        r.revoked = true
        r.cancellationReason = reason
        r.epoch++
        val ids = mutableSetOf(r.id)
        r.children.mapNotNull(records::get).forEach { ids.addAll(revokeTree(it, reason)) }
        queue.remove(r)
        if (r.job?.isCompleted == false) {
            r.state = TaskState.CANCELLING
            publish(r)
        } else finish(r, TaskResult(TaskOutcome.CANCELLED, reason))
        r.watchdog?.cancel(false)
        r.job?.cancel()
        return ids
    }
    fun cancel(id: TaskId): CancelReceipt = synchronized(lock) {
        val ids = records[id]?.let { revokeTree(it) } ?: emptySet()
        pump()
        CancelReceipt(ids)
    }
    /** Synchronous authority revocation precedes job cancellation; occupied lanes release only on actual completion. */
    fun cancelAll(): StopReceipt = synchronized(lock) {
        generation++
        val ids = records.values.toList().flatMap { revokeTree(it) }.toSet()
        StopReceipt(generation, ids)
    }
    override fun close() { synchronized(lock) { closed = true; cancelAll() } }
}
class TaskBudgetExceeded : IllegalStateException("Task budget exhausted")
class TaskContext internal constructor(private val coordinator: TaskCoordinator,
    val taskId: TaskId, val parentId: TaskId?, val instruction: String, val scope: TaskScope,
    val taskEpoch: Long, val stopGeneration: Long, val requestId: RequestId? = null) {
    /** No shared recent history or mutable sibling working state. */
    fun checkActive() = coordinator.checkActive(taskId)
    fun complete(result: TaskResult, publishOutput: () -> Unit) = coordinator.complete(taskId, result, publishOutput)
    /** Invoke immediately before native effect and preserve lower-level snapshot/stop checks. */
    fun beforeAction(capability: TaskCapability) = coordinator.consume(taskId, capability, false)
    fun beforeModelCall() = coordinator.consume(taskId, TaskCapability.MODEL, true)
    fun emit(step: Long, capability: TaskCapability, stage: ReceiptStage) = coordinator.emit(StepReceipt(taskId, step, capability, stage))
    fun delegate(request: ChildRequest): Admission = coordinator.delegate(taskId, request)
}
