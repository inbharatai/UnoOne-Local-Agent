package com.unoone.agent.task

import com.unoone.agent.core.task.*
import com.unoone.agent.core.device.DeviceOutcomeStatus

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

internal fun deviceTaskResult(status: DeviceOutcomeStatus): TaskResult = when (status) {
    DeviceOutcomeStatus.VERIFIED -> TaskResult(TaskOutcome.VERIFIED)
    DeviceOutcomeStatus.NEEDS_USER -> TaskResult(TaskOutcome.NEEDS_USER)
    DeviceOutcomeStatus.FAILED -> TaskResult(TaskOutcome.FAILED, TaskReason.WORKER_FAILED)
    DeviceOutcomeStatus.LIMIT_REACHED -> TaskResult(TaskOutcome.FAILED, TaskReason.BUDGET_EXHAUSTED)
}
