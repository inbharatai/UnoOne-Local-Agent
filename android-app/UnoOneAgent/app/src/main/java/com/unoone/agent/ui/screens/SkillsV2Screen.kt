package com.unoone.agent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unoone.agent.skills.SkillsV2
import com.unoone.agent.ui.viewmodel.SkillsV2Policy
import com.unoone.agent.ui.viewmodel.SkillsV2ViewModel

@Composable
fun SkillsV2Screen(onBack: () -> Unit, model: SkillsV2ViewModel = viewModel()) {
    val versions by model.versions.collectAsState()
    val candidate by model.candidate.collectAsState()
    val status by model.status.collectAsState()
    val running by model.running.collectAsState()
    var selected by remember { mutableStateOf<SkillsV2?>(null) }
    var exportPending by remember { mutableStateOf<SkillsV2?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val skill = exportPending
        if (uri != null && skill != null) model.export(skill, uri)
        exportPending = null
    }
    DisposableEffect(Unit) { onDispose { model.stop() } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("Back to built-in skills") }
        Text("Reviewed workflows", style = MaterialTheme.typography.headlineMedium)
        Text("Immutable native Skills V2 · no model recovery or automatic updates. Manual learning/capture is unavailable in this build. Import reviewed definitions instead.")
        Text("Unknown targets, secrets, payment, final send, CAPTCHA and legal actions remain blocked. No automatic parameter learning or screenshots are stored here.")
        Row {
            TextButton(enabled = !running, onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Text("Import JSON (128 KiB max)") }
            TextButton(onClick = model::stopAll) { Text("Stop all") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (versions.isEmpty()) Text("No approved workflow versions. Import a version 1 definition to review.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(versions, key = { "${it.id}:${it.version}" }) { skill ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${skill.title} · v${skill.version}")
                    Text("${skill.id} · ${skill.risk} · ${skill.steps.size} steps")
                    Text(skill.digest(), style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(enabled = !running, onClick = { selected = skill }) { Text("Review / run") }
                        TextButton(enabled = !running, onClick = {
                            exportPending = skill
                            exporter.launch("${skill.id}-v${skill.version}.json")
                        }) { Text("Export JSON") }
                    }
                } }
            }
        }
    }
    candidate?.let { skill ->
        val baseline = versions.filter { it.id == skill.id }.maxByOrNull { it.version }
        WorkflowReview(skill, SkillsV2Policy.diff(baseline, skill), "Approve exact digest",
            enabled = baseline == null && skill.version == 1,
            onDismiss = model::dismissCandidate,
            onApprove = { model.approve(skill.digest()) })
    }
    selected?.let { skill ->
        WorkflowReview(skill, "Run scope: observe declared apps and only the individually checked steps. Approval lasts for this run only. App versions must match. Native policy can still refuse every action.",
            "Run checked steps", true, { selected = null }) { steps ->
            selected = null
            model.run(skill, skill.digest(), steps)
        }
    }
}

@Composable
private fun WorkflowReview(skill: SkillsV2, explanation: String, button: String, enabled: Boolean,
                           onDismiss: () -> Unit, onApprove: (Set<Int>) -> Unit) {
    var checked by remember(skill.digest()) { mutableStateOf(emptySet<Int>()) }
    var exact by remember(skill.digest()) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${skill.title} · v${skill.version}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(explanation)
            Text("Digest: ${skill.digest()}")
            Text("Risk: ${skill.risk}\nPermissions: ${skill.requiredPermissions}\nApps/version codes: ${skill.appVersions}")
            skill.steps.forEachIndexed { index, step ->
                Text("Step ${index + 1}: ${step.action}")
                Text("Selector: ${step.selector ?: "none"}\nBefore: ${step.preconditions}\nAfter: ${step.postconditions}")
                Row {
                    Checkbox(index in checked, { yes -> checked = if (yes) checked + index else checked - index })
                    Text("Approve this exact step")
                }
            }
            Row { Checkbox(exact, { exact = it }); Text("I reviewed this exact digest and app scope") }
        } },
        confirmButton = { TextButton(enabled = enabled && exact && checked == skill.steps.indices.toSet(),
            onClick = { onApprove(checked.toSet()) }) { Text(button) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
