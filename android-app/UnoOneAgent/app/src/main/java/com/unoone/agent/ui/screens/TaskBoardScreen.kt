package com.unoone.agent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unoone.agent.core.task.Admission
import com.unoone.agent.ui.components.ConfirmationDialog
import com.unoone.agent.ui.viewmodel.*

/** Null delegation callback deliberately exposes no pretend functional button. */
@Composable
fun TaskBoardScreen(onBack: () -> Unit, agentViewModel: AgentViewModel,
    viewModel: TaskBoardViewModel = viewModel(),
    onPrepareResearchDraft: ((String, String) -> Admission)? = null,
    onOwlTask: (() -> Unit)? = null) {
    val recoveredTasks by viewModel.recoveredTasks.collectAsState()
    val journalHealth by viewModel.journalHealth.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear metadata history?") },
        text = { Text("This does not undo actions or restart tasks. Recovered metadata is permanently removed. Clearing is allowed only when all tasks and UI/model owners are idle.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearMetadataHistory() }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } })
    val tasks by viewModel.tasks.collectAsState()
    val results by viewModel.results.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val metadata by viewModel.metadata.collectAsState()
    val receipt by viewModel.receipt.collectAsState()
    val confirmation by agentViewModel.pendingConfirmation.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var requiredPhrasesText by remember { mutableStateOf("") }
    var notesQuery by rememberSaveable { mutableStateOf("") }
    var inputName by rememberSaveable { mutableStateOf(TaskBoardInput.COMMAND.name) }
    val input = TaskBoardInput.valueOf(inputName)
    val rows = TaskBoardStateMapper.rows(tasks, metadata)
    confirmation?.let { (message, level) ->
        ConfirmationDialog(message = message, level = level, onResult = agentViewModel::respondToConfirmation)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to agent") }
                Button(onClick = viewModel::stopAll, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = "Stop all tasks across the app" }) { Text("Stop all") }
            }
            Text("Tasks", style = MaterialTheme.typography.headlineMedium)
            onOwlTask?.let { Button(onClick = it) { Text("GUI-Owl approved screen task") } }
            Text("One phone executor. One model call at a time. Up to two non-UI preparation workers. No parallel robot taps.")
            Text("Enqueue while the phone is busy. Drafts generate text only; they never send it.")
            if (confirmation != null) Text("Paused for your confirmation — use the dialog. This is not a loading state.")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TaskBoardInput.entries.forEach { mode ->
                    OutlinedButton(onClick = { inputName = mode.name }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { this.selected = input == mode }) {
                        Text((if (input == mode) "Selected: " else "") + mode.label)
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("New ${input.label.lowercase()}") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (input == TaskBoardInput.DRAFT) {
                    OutlinedTextField(value = requiredPhrasesText, onValueChange = { requiredPhrasesText = it },
                        label = { Text("Exact required phrases — optional, one per line") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("Up to 6 unique phrases; 80 characters each, 256 combined. Exact case-sensitive checks are not semantic proof. Drafts never send.")
                }
                Button(onClick = { viewModel.submit(text, input, requiredPhrasesText) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Enqueue / send control")
                }
                Text("Exact Stop and pending confirmation replies are controls, not new tasks.", style = MaterialTheme.typography.bodySmall)
                if (onPrepareResearchDraft != null) {
                    OutlinedTextField(value = notesQuery, onValueChange = { notesQuery = it },
                        label = { Text("Local notes search query for a second worker") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (input == TaskBoardInput.DRAFT && requiredPhrasesText.isNotBlank())
                            viewModel.submitPreparation(notesQuery, text, requiredPhrasesText)
                        else viewModel.showAdmission(onPrepareResearchDraft(notesQuery, text), "preparation")
                    },
                        enabled = text.isNotBlank() && notesQuery.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Run notes search + draft workers")
                    }
                    Text("Two independent preparation tasks. The draft does not automatically incorporate note results. Child results stay separate; nothing is sent.")
                } else {
                    Text("Supervised research + draft is not connected yet. Separate drafts and local notes search are available.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (receipt.isNotBlank()) Text(receipt, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        item { Text("Live task graph (${rows.size})", style = MaterialTheme.typography.titleLarge) }
        if (rows.isEmpty()) item { Text("No accepted tasks in this session.") }
        items(rows, key = { it.summary.id.value }) { row ->
            val task = row.summary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Task ${task.id.value}", style = MaterialTheme.typography.titleSmall)
                    Text("Role: ${row.role} · Priority: ${row.priority}")
                    Text(row.stateLabel)
                    Text("Outcome: ${task.outcome?.name ?: "Not reported"}")
                    Text("Parent: ${task.parent?.value ?: "Root"}")
                    Text("Children: ${row.children.joinToString { it.value }.ifEmpty { "None accepted" }}")
                    OutlinedButton(onClick = { viewModel.select(task.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = "Show result for task ${task.id.value}" }) {
                        Text(if (selected == task.id) "Selected task" else "Show this task's result")
                    }
                    if (row.canCancel) OutlinedButton(onClick = { viewModel.cancel(task.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "Cancel task ${task.id.value} and its children" }) { Text("Cancel this task") }
                }
            }
        }
        item {
            Text("Selected task result", style = MaterialTheme.typography.titleLarge)
            Text(selected?.let { "Task ${it.value}" } ?: "Select a task to read its result.")
            // Never fall back to the global timeline or the last task's output.
            Text(TaskBoardStateMapper.selectedText(selected, results.mapValues { it.value.text })
                ?: "No result text for this selection. Read its state and outcome above.")
        }
        item {
            Text("Recovery review", style = MaterialTheme.typography.titleLarge)
            Text("Metadata only. Nonterminal tasks need review; terminal states are preserved. Nothing is resumed or replayed. Review what happened and explicitly enter a new command for remaining work.")
            Text("Journal: $journalHealth")
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear metadata history / recover journal") }
            if (recoveredTasks.isEmpty()) Text("No recovered metadata.")
        }
        items(recoveredTasks, key = { "recovery-${it.id.value}-${it.sequence}" }) { task ->
            Text("${task.id.value} · ${task.state.name} · parent ${task.parent?.value ?: "Root"} · source ${task.source.name} · sequence ${task.sequence}")
        }
    }
}
