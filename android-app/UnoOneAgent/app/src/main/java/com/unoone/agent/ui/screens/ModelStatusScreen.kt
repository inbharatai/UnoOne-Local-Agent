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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.brain.BrainSelfTestResult
import com.unoone.agent.ui.theme.DoneGreen
import com.unoone.agent.ui.theme.FailedRed
import com.unoone.agent.ui.viewmodel.ModelStatusViewModel

/** Model health, installation and sole-brain qualification screen. */
@Composable
fun ModelStatusScreen(viewModel: ModelStatusViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val storageUsageMb by viewModel.storageUsageMb.collectAsState()
    val resultMessage by viewModel.resultMessage.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val brainStatus by viewModel.brainStatus.collectAsState()
    val selfTest by viewModel.selfTest.collectAsState()
    val brainBusy by viewModel.brainBusy.collectAsState()

    LaunchedEffect(resultMessage) {
        if (resultMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.consumeResultMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text(
                text = "Local Models",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::refresh, enabled = !busy && !brainBusy) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        Text(
            text = "Storage used: $storageUsageMb MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(12.dp))

        resultMessage?.let { message ->
            Text(
                text = message,
                color = if (message.contains("failed", ignoreCase = true)) FailedRed else DoneGreen,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        progress?.let { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(item.message, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { item.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        brainStatus?.let { brain ->
            BrainCard(
                row = brain,
                selfTest = selfTest,
                busy = brainBusy,
                onLoad = viewModel::loadBrain,
                onSelfTest = viewModel::runBrainSelfTest
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Advanced Model Diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Technical artifacts are shown here for installation, repair and integrity checks. Use Offline Languages to install or activate speech packs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Text("No models declared in the bundled manifest.")
        } else {
            rows.forEach { row ->
                ModelRowCard(row, busy, viewModel::installModel, viewModel::uninstallModel)
            }
        }
    }
}

@Composable
private fun BrainCard(
    row: ModelStatusViewModel.BrainStatusRow,
    selfTest: BrainSelfTestResult?,
    busy: Boolean,
    onLoad: () -> Unit,
    onSelfTest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("UnoOne Brain", style = MaterialTheme.typography.labelLarge)
            Text(row.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(row.description, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            DetailLine("Runtime", "LiteRT-LM")
            DetailLine("Installed", if (row.installed) "Yes" else "No")
            DetailLine(
                "Integrity",
                if (row.installed && row.isDeviceVerified) "Device-qualified" else "Qualification pending"
            )
            DetailLine(
                "Memory gate",
                "${row.minimumRamMb} MB minimum · ${row.recommendedRamMb} MB recommended"
            )
            DetailLine(
                "Loaded",
                when {
                    row.isLoaded -> "Yes — ${row.backend}"
                    row.installed -> "No"
                    else -> "No (artifact not installed)"
                }
            )
            if (row.lastLoadError.isNotBlank()) DetailLine("Last load error", row.lastLoadError)

            selfTest?.takeIf { it.manifestId == row.manifestId }?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                DetailLine(
                    "Self-test",
                    when {
                        !result.installed -> "Artifact not installed"
                        !result.loaded -> "Load failed: ${result.loadError}"
                        result.proposedTool == null -> "Loaded on ${result.backend}; no tool proposed"
                        result.toolAccepted -> "Passed on ${result.backend} in ${result.elapsedMs} ms"
                        else -> "Rejected proposed tool '${result.proposedTool}'"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(onClick = onLoad, enabled = row.installed) { Text("Load Brain") }
                    Button(onClick = onSelfTest, enabled = row.installed) { Text("Run Self-Test") }
                }
            }
        }
    }
}

@Composable
private fun ModelRowCard(
    row: ModelStatusViewModel.ModelRow,
    busy: Boolean,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        row.folder,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Icon(
                    imageVector = if (row.healthy) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = if (row.healthy) "Healthy" else "Missing or unhealthy",
                    tint = if (row.healthy) DoneGreen else FailedRed
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            DetailLine("Type", row.type.uppercase())
            DetailLine("Version", row.version.ifBlank { "—" })
            DetailLine("Language", row.language)
            DetailLine(
                "Status",
                when {
                    row.verified -> "Present and hash-verified"
                    row.healthy -> "Present but not hash-verified"
                    row.present -> "Present; repair required"
                    else -> "Not installed"
                }
            )
            DetailLine("Size", if (row.sizeMb > 0) "${row.sizeMb} MB" else "—")
            DetailLine("Backend", row.backend)
            DetailLine("Minimum RAM", if (row.minRamMb > 0) "${row.minRamMb} MB" else "—")
            DetailLine("SHA-256", row.sha256Preview, mono = true)
            if (!row.verified) DetailLine("Health", row.healthMessage)

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                } else if (!row.healthy) {
                    Button(onClick = { onInstall(row.id) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Text(if (row.present) "Repair" else "Install", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (row.present && !busy) {
                    OutlinedButton(onClick = { onUninstall(row.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("Uninstall", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.weight(0.35f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.65f)
        )
    }
}
