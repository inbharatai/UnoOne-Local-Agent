package com.unoone.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.ui.theme.DoneGreen
import com.unoone.agent.ui.theme.FailedRed
import com.unoone.agent.ui.theme.SafetyOrange
import com.unoone.agent.ui.viewmodel.AuditViewerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit Viewer: a filterable, read-only view of the recent action log (the same store
 * [com.unoone.agent.safety.AuditLogger] writes to). Raw input is redacted. Filter by tool
 * substring, status, and "elevated risk only". Reached from Settings.
 */
@Composable
fun AuditViewerScreen(viewModel: AuditViewerViewModel, onBack: () -> Unit) {
    val rows by viewModel.rows.collectAsState()
    val toolFilter by viewModel.toolFilter.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val onlyElevated by viewModel.onlyElevatedRisk.collectAsState()
    val sdf = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text(
                text = "Audit Log",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = toolFilter,
            onValueChange = viewModel::setToolFilter,
            label = { Text("Filter by tool") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuditViewerViewModel.StatusFilter.entries.forEach { f ->
                FilterChip(
                    selected = statusFilter == f,
                    onClick = { viewModel.setStatusFilter(f) },
                    label = { Text(f.label) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Elevated risk only", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
            Switch(checked = onlyElevated, onCheckedChange = viewModel::setOnlyElevatedRisk)
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text("${rows.size} entries", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                AuditRowCard(row, sdf)
            }
        }
    }
}

@Composable
private fun AuditRowCard(row: AuditViewerViewModel.AuditRow, sdf: SimpleDateFormat) {
    val (statusColor, statusLabel) = when (row.status.lowercase()) {
        "success" -> DoneGreen to "SUCCESS"
        "blocked" -> FailedRed to "BLOCKED"
        "failed" -> SafetyOrange to "FAILED"
        else -> MaterialTheme.colorScheme.onSurface to row.status.uppercase()
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sdf.format(Date(row.timestamp)), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(statusLabel, style = MaterialTheme.typography.labelMedium, color = statusColor, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tool: ${row.tool.ifBlank { "—" }}  ·  risk ${row.riskLevel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Input:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(row.input, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            if (!row.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Error: ${row.errorMessage}", style = MaterialTheme.typography.bodySmall, color = FailedRed)
            }
        }
    }
}