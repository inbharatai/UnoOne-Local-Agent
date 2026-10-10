package com.unoone.agent.ui.viewmodel

import com.unoone.agent.core.task.*
import org.junit.Assert.*
import org.junit.Test

class TaskBoardStateMapperTest {
    private fun task(id: String, state: TaskState, parent: String? = null) = TaskSummary(
        TaskId(id), parent?.let(::TaskId), TaskSource.NATIVE, state, 1)

    @Test fun everyRealStateHasExplicitLabelAndOnlyActiveTasksCanCancel() {
        TaskState.entries.forEach { state ->
            val row = TaskBoardStateMapper.rows(listOf(task("opaque", state))).single()
            assertTrue(row.stateLabel.isNotBlank())
            assertEquals(state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.WAITING_CHILD), row.canCancel)
        }
    }
    @Test fun outputNeverLeaksFromAnotherTaskOrUnselectedTask() {
        val texts = mapOf(TaskId("a") to "private a", TaskId("b") to "private b")
        assertNull(TaskBoardStateMapper.selectedText(null, texts))
        assertNull(TaskBoardStateMapper.selectedText(TaskId("missing"), texts))
        assertEquals("private b", TaskBoardStateMapper.selectedText(TaskId("b"), texts))
    }
    @Test fun graphUsesOnlyActualAcceptedParentRelations() {
        val parent = task("p", TaskState.WAITING_CHILD).copy(role = WorkerKind("preparation"), priority = TaskPriority.MAINTENANCE)
        val rows = TaskBoardStateMapper.rows(listOf(parent, task("c", TaskState.QUEUED, "p")))
        assertEquals(listOf(TaskId("c")), rows.first().children)
        assertEquals("preparation", rows.first().role)
        assertEquals("MAINTENANCE", rows.first().priority)
    }
    @Test fun receiptsNeverInventAdmission() {
        RejectionReason.entries.forEach {
            val label = TaskBoardStateMapper.receipt(Admission.Rejected(it))
            assertTrue(label.contains(it.name))
            assertTrue(label.contains("No new task"))
        }
        assertTrue(TaskBoardStateMapper.receipt(Admission.Accepted(TaskId("opaque"), TaskState.QUEUED)).contains("opaque"))
    }
    @Test fun recoveryAndNeedsUserAreNotRunningSpinners() {
        assertTrue(TaskBoardStateMapper.label(TaskState.NEEDS_REVIEW).contains("cannot resume or replay"))
        assertTrue(TaskBoardStateMapper.label(TaskState.NEEDS_USER).contains("Paused"))
        assertTrue(TaskBoardStateMapper.label(TaskState.UNVERIFIED).contains("do not assume"))
        assertTrue(TaskBoardStateMapper.label(TaskState.ACTION_VERIFIED).contains("wider task completion was not established"))
    }
}
