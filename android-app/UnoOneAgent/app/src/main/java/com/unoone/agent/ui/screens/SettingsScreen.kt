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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val modelStatuses by viewModel.modelStatuses.collectAsState()
    val storageUsageMb by viewModel.storageUsageMb.collectAsState()
    var darkMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Model Status
        SettingsSection(
            title = "Model Status",
            action = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        ) {
            if (modelStatuses.isEmpty()) {
                Text("No models detected. Push models via ADB and tap refresh.")
            } else {
                modelStatuses.forEach { status ->
                    StatusRow(label = status.name, present = status.present, sizeMb = status.sizeMb)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Voice Tests
        SettingsSection(title = "Voice Tests") {
            Button(onClick = { /* STT test */ }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("Run STT Test", modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { /* TTS test */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Run TTS Test")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Storage
        SettingsSection(title = "Storage") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Local storage usage")
                Text("$storageUsageMb MB", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { /* clear logs */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear Local Logs")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { /* export logs */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Export Logs")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Diagnostics
        SettingsSection(title = "Diagnostics") {
            Text("STT latency: —")
            Text("Model latency: —")
            Text("TTS latency: —")
            Text("Action success rate: —")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Appearance
        SettingsSection(title = "Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark mode")
                Switch(checked = darkMode, onCheckedChange = { darkMode = it })
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "UnoOne v1.0.0-local",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                action?.invoke()
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, present: Boolean, sizeMb: Long = 0) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sizeMb > 0) {
                Text(
                    text = "${sizeMb}MB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Icon(
                imageVector = if (present) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = if (present) "Present" else "Missing",
                tint = if (present) Color(0xFF2ECC71) else Color(0xFFE74C3C)
            )
        }
    }
}
