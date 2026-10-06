package com.unoone.agent.core.task

import java.util.UUID

@JvmInline value class TaskId(val value: String)
@JvmInline value class RequestId(val value: String)
@JvmInline value class WorkerKind(val value: String)

enum class TaskSource { TEXT, VOICE, SKILL, BROWSER, NATIVE }
enum class TaskPriority(val rank: Int) { MAINTENANCE(0), NORMAL(1), INTERACTIVE(2) }
enum class TaskCapability { UI_READ, UI_WRITE, MODEL, LOCAL_READ, LOCAL_WRITE, BROWSER, AUDIO }
enum class TaskState { QUEUED, RUNNING, CANCELLING, WAITING_CHILD, NEEDS_USER, SUCCEEDED, UNVERIFIED, FAILED, CANCELLED, NEEDS_REVIEW }
enum class TaskOutcome { VERIFIED, RESPONDED, UNVERIFIED, NEEDS_USER, FAILED, CANCELLED }
enum class TaskReason { NONE, WORKER_FAILED, STOPPED, BUDGET_EXHAUSTED, CHILD_FAILED, REVIEW_REQUIRED }
enum class WorkerLane { INTERACTIVE, BACKGROUND }

data class TaskScope(
    val capabilities: Set<TaskCapability> = emptySet(),
    val packages: Set<String> = emptySet(),
    val origins: Set<String> = emptySet(),
    val objectHandles: Set<String> = emptySet()
) {
    fun isSubsetOf(parent: TaskScope) = parent.capabilities.containsAll(capabilities) &&
        parent.packages.containsAll(packages) && parent.origins.containsAll(origins) &&
        parent.objectHandles.containsAll(objectHandles)
    internal fun frozen() = copy(
        capabilities = java.util.Collections.unmodifiableSet(HashSet(capabilities)),
        packages = java.util.Collections.unmodifiableSet(HashSet(packages)),
        origins = java.util.Collections.unmodifiableSet(HashSet(origins)),
        objectHandles = java.util.Collections.unmodifiableSet(HashSet(objectHandles)))
}
data class TaskBudget(val wallMillis: Long = 120_000, val actions: Int = 32, val modelCalls: Int = 16) {
    init { require(wallMillis in 1..3_600_000 && actions in 0..1024 && modelCalls in 0..256) }
}
/** Instructions are memory-only. Never serialize a request to the journal. */
data class TaskRequest(
    val requestId: RequestId,
    val worker: WorkerKind,
    val instruction: String,
    val scope: TaskScope,
    val capturedStopGeneration: Long,
    val source: TaskSource = TaskSource.TEXT,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val budget: TaskBudget = TaskBudget()
)
data class ChildRequest(val requestId: RequestId, val worker: WorkerKind, val instruction: String, val scope: TaskScope)
enum class RejectionReason { QUEUE_FULL, STALE_EPOCH, UNKNOWN_WORKER, INVALID_REQUEST, SCOPE_ESCALATION, CHILD_LIMIT, DEPTH_LIMIT, PARENT_NOT_RUNNING, CLOSED, PREPARING }
sealed interface Admission {
    data class Accepted(val taskId: TaskId, val state: TaskState, val duplicate: Boolean = false) : Admission
    data class Rejected(val reason: RejectionReason) : Admission
}
data class TaskResult(val outcome: TaskOutcome, val reason: TaskReason = TaskReason.NONE)
sealed interface WorkerResult {
    data class Finished(val result: TaskResult) : WorkerResult
    /** No worker continuation is retained. Coordinator completes parent from child outcomes. */
    data object WaitingForChildren : WorkerResult
}
fun interface NativeTaskWorker { suspend fun run(context: TaskContext): WorkerResult }
/** Only trusted composition-root code creates registrations. Requests have no lane or closure. */
data class WorkerRegistration(val kind: WorkerKind, val lane: WorkerLane, val worker: NativeTaskWorker)

data class TaskSummary(val id: TaskId, val parent: TaskId?, val source: TaskSource, val state: TaskState,
    val sequence: Long, val outcome: TaskOutcome? = null,
    val role: WorkerKind = WorkerKind("legacy"), val priority: TaskPriority = TaskPriority.NORMAL)

enum class ReceiptStage { PREPARED, DISPATCH_INTENT, DISPATCHED, VERIFIED, UNVERIFIED, BLOCKED, CANCELLED }
data class StepReceipt(val taskId: TaskId, val step: Long, val capability: TaskCapability, val stage: ReceiptStage)
/** Closed metadata fields only: no instruction, target, URL, user text, or worker-supplied message. */
data class TaskJournalEvent(val version: Int = 1, val sequence: Long, val summary: TaskSummary,
    val receipt: StepReceipt? = null) {
    fun recoveredSummary(): TaskSummary = if (summary.state in setOf(TaskState.SUCCEEDED,
        TaskState.UNVERIFIED, TaskState.FAILED, TaskState.CANCELLED)) summary
        else summary.copy(state = TaskState.NEEDS_REVIEW, outcome = null)
}
data class CancelReceipt(val cancelled: Set<TaskId>)
data class StopReceipt(val generation: Long, val cancelled: Set<TaskId>)

data class TicketBinding(val taskId: TaskId, val taskEpoch: Long, val stopGeneration: Long,
    val step: Long, val expiresAtMillis: Long, val nonce: String = UUID.randomUUID().toString())
data class ApprovalTicket(val binding: TicketBinding, val capability: TaskCapability, val actionDigest: String, val observationEpoch: Long)
data class PermissionTicket(val binding: TicketBinding, val capability: TaskCapability)
data class ResumeTicket(val binding: TicketBinding)
/** Tickets are ephemeral validation data, not persisted continuations or replay authority. */
enum class TicketDecision { VALID_RECHECK_REQUIRED, STALE }
