package com.unoone.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.languagepacks.LanguagePackStatus
import com.unoone.agent.ui.viewmodel.LanguagePackRow
import com.unoone.agent.ui.viewmodel.LanguagePacksViewModel

@Composable
fun LanguagePacksScreen(viewModel: LanguagePacksViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsState()
    val busyPackId by viewModel.busyPackId.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Offline Languages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Download once, then use STT and TTS locally",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }

        progress?.let { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.message, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        rows.forEach { row ->
            LanguagePackCard(
                row = row,
                busy = busyPackId == row.id,
                globalBusy = busyPackId != null,
                onInstall = { viewModel.install(row.id) },
                onActivate = { viewModel.activate(row.id) },
                onUninstall = { viewModel.uninstall(row.id) }
            )
        }
    }
}

@Composable
private fun LanguagePackCard(
    row: LanguagePackRow,
    busy: Boolean,
    globalBusy: Boolean,
    onInstall: () -> Unit,
    onActivate: () -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null)
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(row.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(row.nativeName, style = MaterialTheme.typography.bodyLarge)
                }
                if (row.active) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                when (row.status) {
                    LanguagePackStatus.baseline -> "Baseline"
                    LanguagePackStatus.planned -> "Planned"
                    LanguagePackStatus.beta -> "Beta"
                    LanguagePackStatus.stable -> "Stable"
                    LanguagePackStatus.deprecated -> "Deprecated"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(row.healthSummary, style = MaterialTheme.typography.bodySmall)
            if (row.notes.isNotBlank()) {
                Text(
                    row.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (busy) {
                CircularProgressIndicator()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        !row.downloadable -> {
                            OutlinedButton(onClick = {}, enabled = false) { Text("Qualification pending") }
                        }
                        !row.healthy -> {
                            Button(onClick = onInstall, enabled = !globalBusy) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Text(if (row.installed) "Repair" else "Download", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        !row.active -> {
                            Button(onClick = onActivate, enabled = !globalBusy) { Text("Activate") }
                        }
                    }

                    if (row.installed && row.removable && !row.active) {
                        OutlinedButton(onClick = onUninstall, enabled = !globalBusy) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text("Remove", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
