package com.unoone.agent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unoone.agent.core.latency.*
import com.unoone.agent.voice.*

/** Local volatile metadata. Export occurs only through the user's document picker. */
@Composable
fun LatencyDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recorder = VoiceLatency.recorder
    var enabled by remember { mutableStateOf(recorder.enabled) }
    var revision by remember { mutableIntStateOf(0) }
    var cue by remember { mutableStateOf(ListeningCuePolicy.preference(context)) }
    var notice by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) notice = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(recorder.exportJson().toByteArray(Charsets.UTF_8)) }
                ?: error("Document unavailable")
            "Metadata exported to your selected document."
        }.getOrDefault("Export failed.")
    }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Latency diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text("Off by default. Volatile timing metadata only: no transcripts, screenshots, prompts, user labels or raw task IDs. No automatic uploads. Disabling clears traces.")
        Row { Text("Collect timing metadata"); Switch(enabled, { enabled = it; recorder.enabled = it; revision++ }) }
        Button(onClick = { recorder.clear(); revision++ }) { Text("Clear") }
        Button(onClick = { revision++ }) { Text("Refresh") }
        Button(onClick = { export.launch("unoone-latency.json") }) { Text("Export JSON…") }
        Text(notice)
        Text("Listening cue (touch exploration always uses spoken)")
        ListeningCue.entries.forEach { option ->
            TextButton(onClick = { cue = option; ListeningCuePolicy.setPreference(context, option) }) {
                Text((if (cue == option) "Selected: " else "") + if (option == ListeningCue.SPOKEN) "Spoken (default)" else "Visual / haptic (where supported)")
            }
        }
        Text("Pilot samples, not a phone performance claim. Playback is a wait-completion proxy, not audible onset. TTFT: No data / not measured.")
        val rows = remember(revision) { recorder.snapshots() }
        Text("Retained ${rows.size}; evicted ${recorder.evictionCount()}; dropped events ${rows.sumOf { it.drops }}")
        Text("Failed ${rows.count { it.outcome == LatencyOutcome.FAILED }}; cancelled ${rows.count { it.outcome == LatencyOutcome.CANCELLED }}; timeout ${rows.count { it.outcome == LatencyOutcome.TIMED_OUT }}; open/censored ${rows.count { it.outcome == null || it.outcome == LatencyOutcome.INTERRUPTED }}")
        rows.flatMap { row -> row.events.map { Triple(row.origin, row.path, it.profile) } }.distinct().forEach { (origin, path, profile) ->
            val summary = recorder.summary(LatencyStage.MIC_REQUEST, LatencyStage.RESULT_READY, path, origin, profile)
            fun value(us: Long?) = us?.let { "${it / 1000.0} ms" } ?: "No data"
            Text("$origin / $path / $profile — n=${summary.samples}/${summary.eligible}, p50 ${value(summary.p50Us)}, p95 ${value(summary.p95Us)}")
        }
        if (rows.isEmpty()) Text("No data")
    }
}
