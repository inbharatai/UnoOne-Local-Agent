package com.unoone.agent.task

import com.unoone.agent.core.task.*
import com.unoone.agent.core.device.DeviceOutcomeStatus
import com.unoone.agent.NativeDeviceGoal

/** Shared classification; accounting happens at the actual ActionExecutor boundary. */
internal object NativeToolEffects {
    fun capability(tool: String): TaskCapability = when (tool) {
        "search_notes", "list_notes", "read_note" -> TaskCapability.LOCAL_READ
        "save_note", "create_note", "delete_notes", "delete_all_notes" -> TaskCapability.LOCAL_WRITE
        "read_screen", "ocr_screen", "describe_scene" -> TaskCapability.UI_READ
        "secure_browser_task" -> TaskCapability.BROWSER
        "record_voice_note", "speak" -> TaskCapability.AUDIO
        else -> TaskCapability.UI_WRITE
    }
}

/** A bounded native postcondition is evidence for its step, not necessarily the user's wider task. */
internal fun deviceTaskResult(status: DeviceOutcomeStatus, goal: NativeDeviceGoal): TaskResult = when (status) {
    DeviceOutcomeStatus.VERIFIED -> TaskResult(if (goal.isActionOnly()) TaskOutcome.ACTION_VERIFIED else TaskOutcome.VERIFIED)
    DeviceOutcomeStatus.NEEDS_USER -> TaskResult(TaskOutcome.NEEDS_USER)
    DeviceOutcomeStatus.FAILED -> TaskResult(TaskOutcome.FAILED, TaskReason.WORKER_FAILED)
    DeviceOutcomeStatus.LIMIT_REACHED -> TaskResult(TaskOutcome.FAILED, TaskReason.BUDGET_EXHAUSTED)
}

private fun NativeDeviceGoal.isActionOnly(): Boolean = when (this) {
    is NativeDeviceGoal.OpenApp, is NativeDeviceGoal.ReadScreen -> false
    is NativeDeviceGoal.Current -> command != "read"
    is NativeDeviceGoal.Sequence -> goals.any { it.isActionOnly() }
    // Find establishes visible text, not the identity or completion of the referenced chat/task.
    is NativeDeviceGoal.Find, is NativeDeviceGoal.Click, is NativeDeviceGoal.SetField,
    is NativeDeviceGoal.Interact, is NativeDeviceGoal.Back, is NativeDeviceGoal.Scroll,
    is NativeDeviceGoal.NeedsUser -> true
}

internal fun deviceTaskReport(result: TaskResult, reason: String): String =
    if (result.outcome == TaskOutcome.ACTION_VERIFIED)
        "Action verified. The wider task was not established as complete. $reason"
    else reason
