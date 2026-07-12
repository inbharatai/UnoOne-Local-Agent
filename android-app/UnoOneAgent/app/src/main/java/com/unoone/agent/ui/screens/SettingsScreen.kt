package com.unoone.agent.ui.screens

import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.ui.viewmodel.SettingsViewModel
import com.unoone.agent.voice.VoiceLanguage

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToVoiceTest: () -> Unit = {},
    onNavigateToAudit: () -> Unit = {},
    onNavigateToSecureBrowser: () -> Unit = {}
) {
    val modelStatuses by viewModel.modelStatuses.collectAsState()
    val storageUsageMb by viewModel.storageUsageMb.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()
    val voiceLanguage by viewModel.voiceLanguage.collectAsState()
    var showClearConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Model Status",
            action = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh model status")
                }
            }
        ) {
            if (modelStatuses.isEmpty()) {
                Text("No model artifacts detected.")
            } else {
                modelStatuses.forEach { status ->
                    StatusRow(status.name, status.present, status.sizeMb)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsSection(title = "Manage") {
            ManageButton("Model Status & Install", Icons.Default.Memory, onNavigateToModels)
            ManageButton("Voice Test (STT / TTS)", Icons.Default.Mic, onNavigateToVoiceTest)
            ManageButton("Secure Browser (PageAgent)", Icons.Default.Language, onNavigateToSecureBrowser)
            ManageButton("Audit Log", Icons.AutoMirrored.Filled.ReceiptLong, onNavigateToAudit)
            Text(
                "Secure Browser reserves Gemma 4 exclusively, automates approved HTTPS pages through Alibaba PageAgent, and requires manual control for credentials, OTP, CAPTCHA, payments and legal declarations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsSection(title = "Voice language") {
            VoiceLanguage.SUPPORTED.forEach { lang ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setVoiceLanguage(lang.code) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(lang.display)
                    if (lang.code == voiceLanguage) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected language",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                "This is the current compatibility list. The new signed language-pack catalogue includes English, Assamese and 11 additional Indian languages; planned packs remain disabled until qualified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
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
            Button(onClick = { showClearConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear Local Logs")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.exportLogs(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Export Logs")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsSection(title = "Privacy") {
            Button(onClick = onNavigateToPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text("Privacy Settings")
            }
            Text(
                "Control optional online tools and data sharing. Gemma, installed speech packs and PageAgent planning run locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsSection(title = "Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark mode")
                Switch(
                    checked = darkMode,
                    onCheckedChange = { enabled ->
                        viewModel.setDarkMode(enabled)
                        AppCompatDelegate.setDefaultNightMode(
                            if (enabled) AppCompatDelegate.MODE_NIGHT_YES
                            else AppCompatDelegate.MODE_NIGHT_NO
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "UnoOne v0.4.0-alpha-v2 · Gemma 4 E2B candidate",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear All Logs?") },
            text = { Text("This permanently deletes local action logs and cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        showClearConfirmation = false
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ManageButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = label)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingsSection(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sizeMb > 0) {
                Text(
                    "$sizeMb MB",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Icon(
                imageVector = if (present) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = if (present) "Model present" else "Model missing",
                tint = if (present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
