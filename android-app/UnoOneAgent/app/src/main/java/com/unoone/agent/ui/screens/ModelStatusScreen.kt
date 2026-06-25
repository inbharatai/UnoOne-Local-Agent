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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.ui.theme.DoneGreen
import com.unoone.agent.ui.theme.FailedRed
import com.unoone.agent.ui.viewmodel.ModelStatusViewModel

/**
 * Model Status / Settings screen: lists every manifest model with version, size, health, backend,
 * and SHA-256 indicator. Supports install (with a live progress bar) and uninstall. Reached from
 * Settings (not in the bottom nav).
 */
@Composable
fun ModelStatusScreen(viewModel: ModelStatusViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val storageUsageMb by viewModel.storageUsageMb.collectAsState()
    val resultMessage by viewModel.resultMessage.collectAsState()
    val busy by viewModel.busy.collectAsState()

    LaunchedEffect(resultMessage) {
        // Auto-clear the one-shot result message after a few seconds.
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
                text = "Model Status",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::refresh, enabled = !busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Local storage used: $storageUsageMb MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(12.dp))

        progress?.let { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(p.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { p.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        resultMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodyMedium,
                color = if (msg.startsWith("Install failed") || msg.startsWith("Uninstall")) MaterialTheme.colorScheme.error
                else DoneGreen,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (rows.isEmpty()) {
            Text("No models declared in manifest. Ensure models_manifest.json is bundled.")
        } else {
            rows.forEach { row -> ModelRowCard(row, busy, viewModel::installModel, viewModel::uninstallModel) }
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.folder, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${row.type.uppercase()} · v${row.version.ifBlank { "—" }} · ${row.language}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Icon(
                    imageVector = if (row.healthy) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = if (row.healthy) "Healthy" else "Missing/corrupt",
                    tint = if (row.healthy) DoneGreen else FailedRed
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            DetailLine("Status", if (row.healthy) "Present & verified" else if (row.present) "Present (needs repair)" else "Not installed")
            DetailLine("Size", if (row.sizeMb > 0) "${row.sizeMb} MB" else "—")
            DetailLine("Backend", row.backend)
            DetailLine("Min RAM", if (row.minRamMb > 0) "${row.minRamMb} MB" else "any")
            DetailLine("SHA-256", row.sha256Preview, mono = true)
            if (!row.healthy) DetailLine("Health", row.healthMessage)

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else if (!row.healthy) {
                    Button(onClick = { onInstall(row.id) }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(if (row.present) "Repair" else "Install")
                    }
                }
                if (row.present && !busy) {
                    OutlinedButton(onClick = { onUninstall(row.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Uninstall")
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}