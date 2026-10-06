package com.unoone.agent.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.task.*
import com.unoone.agent.voice.VoiceControlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskBoardInput(val label: String, val worker: String) {
    COMMAND("Phone command", "command"), DRAFT("Draft only", "draft"), NOTES("Local notes search", "notes-search")
}

class TaskBoardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as UnoOneApplication
    private val runtime = app.taskRuntime
    val tasks = runtime.tasks
    val results = runtime.results
    private val mutableRecovered = MutableStateFlow(runtime.recoveredTasks)
    val recoveredTasks = mutableRecovered.asStateFlow()
    private val mutableJournalHealth = MutableStateFlow(runtime.journalHealth)
    val journalHealth = mutableJournalHealth.asStateFlow()
    fun clearMetadataHistory() {
        val cleared = runtime.clearMetadataHistory()
        mutableRecovered.value = runtime.recoveredTasks
        mutableJournalHealth.value = runtime.journalHealth
        if (cleared) mutableSelected.value = null
        mutableReceipt.value = if (cleared) "Metadata history cleared. No actions undone and no tasks restarted."
            else "History not cleared. Wait until all tasks and UI/model owners are idle; journal failures remain blocked."
    }
    private val mutableReceipt = MutableStateFlow("")
    val receipt = mutableReceipt.asStateFlow()
    private val mutableSelected = MutableStateFlow<TaskId?>(null)
    val selected = mutableSelected.asStateFlow()
    private val mutableMetadata = MutableStateFlow<Map<TaskId, TaskBoardReceiptMetadata>>(emptyMap())
    val metadata = mutableMetadata.asStateFlow()

    fun select(id: TaskId) { mutableSelected.value = id }
    fun submit(text: String, input: TaskBoardInput, requiredPhrasesText: String = "") {
        // Exact controls bypass admission even while every execution slot is occupied.
        if (VoiceControlPolicy.isStop(text)) { stopAll(); return }
        if (app.orchestrator.resolvePendingVoiceConfirmation(text)) {
            mutableReceipt.value = "Confirmation control handled. No new task was enqueued."
            return
        }
        if (text.isBlank()) { mutableReceipt.value = "Enter a new command, draft request, or notes query."; return }
        val admission = when (input) {
            TaskBoardInput.COMMAND -> runtime.submitCommand(text)
            TaskBoardInput.DRAFT -> runtime.submitDraft(text, requiredPhrasesText.lines().filter { it.isNotBlank() })
            TaskBoardInput.NOTES -> runtime.submitNotesSearch(text)
        }
        showAdmission(admission, input.worker)
    }
    fun submitPreparation(query: String, text: String, requiredPhrasesText: String = "") {
        showAdmission(runtime.submitPreparation(query, text,
            requiredPhrasesText.lines().filter { it.isNotBlank() }), "preparation")
    }
    fun showAdmission(admission: Admission, role: String) {
        mutableReceipt.value = TaskBoardStateMapper.receipt(admission)
        if (admission is Admission.Accepted) {
            mutableMetadata.value = mutableMetadata.value + (admission.taskId to TaskBoardReceiptMetadata(role))
            mutableSelected.value = admission.taskId
        }
    }
    fun cancel(id: TaskId) {
        val receipt = runtime.cancelTask(id)
        mutableReceipt.value = if (receipt.cancelled.isEmpty()) "No active task cancelled for ${id.value}."
            else "Cancellation applied to ${receipt.cancelled.size} task(s): ${receipt.cancelled.joinToString { it.value }}. Effects already completed are not undone."
    }
    fun stopAll() {
        GlobalTaskCancellation.cancelAll()
        mutableReceipt.value = "Stop all requested across the app. Queued/running task authority revoked; native work may still be draining. Completed effects are not undone."
    }
}
