package com.unoone.agent.ui.viewmodel

import com.unoone.agent.core.task.*

/** Presentation only: coordinator state is authoritative, never infer success from output text. */
data class TaskBoardRow(val summary: TaskSummary, val role: String, val priority: String,
    val stateLabel: String, val canCancel: Boolean, val children: List<TaskId>)
data class TaskBoardReceiptMetadata(val role: String, val priority: TaskPriority = TaskPriority.NORMAL)

object TaskBoardStateMapper {
    fun rows(tasks: List<TaskSummary>, metadata: Map<TaskId, TaskBoardReceiptMetadata> = emptyMap()): List<TaskBoardRow> =
        tasks.sortedBy { it.sequence }.map { task ->
            TaskBoardRow(task, task.role.value,
                task.priority.name, label(task.state),
                task.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_CHILD),
                tasks.filter { it.parent == task.id }.map { it.id })
        }
    fun label(state: TaskState): String = when (state) {
        TaskState.QUEUED -> "Queued — waiting for a resource slot"
        TaskState.RUNNING -> "Running — may be waiting for the single model or phone executor"
        TaskState.CANCELLING -> "Cancelling — waiting for native cleanup; slot remains occupied"
        TaskState.WAITING_CHILD -> "Waiting for accepted child tasks"
        TaskState.NEEDS_USER -> "Paused / needs you — check permission or confirmation; issue remaining steps explicitly"
        TaskState.SUCCEEDED -> "Completed — see reported outcome"
        TaskState.UNVERIFIED -> "Unverified — do not assume the action succeeded"
        TaskState.FAILED -> "Failed"
        TaskState.CANCELLED -> "Cancelled — already completed effects are not undone"
        TaskState.NEEDS_REVIEW -> "Needs review — metadata only; cannot resume or replay"
    }
    fun selectedText(selected: TaskId?, texts: Map<TaskId, String>): String? = selected?.let(texts::get)
    fun receipt(admission: Admission): String = when (admission) {
        is Admission.Accepted -> "${if (admission.duplicate) "Existing receipt" else "Accepted"}: ${admission.taskId.value} · ${admission.state.name}"
        is Admission.Rejected -> "Not accepted: ${admission.reason.name}. No new task was started."
    }
}
