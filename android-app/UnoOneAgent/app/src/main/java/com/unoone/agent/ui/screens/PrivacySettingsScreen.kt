package com.unoone.agent.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.ui.viewmodel.PrivacySettingsViewModel

/**
 * 4E: Privacy Settings Screen
 * Allows users to control which online services the app can use.
 * All settings default to OFF (privacy-first).
 * Stored in EncryptedSharedPreferences for security.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    viewModel: PrivacySettingsViewModel,
    onBack: () -> Unit
) {
    val bhashiniTts by viewModel.bhashiniTtsEnabled.collectAsState()
    val onlineRag by viewModel.onlineRagEnabled.collectAsState()
    val onlineSttFallback by viewModel.onlineSttFallbackEnabled.collectAsState()
    val analyticsSharing by viewModel.analyticsSharingEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Custom top bar since we're inside a NavHost Scaffold
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to settings"
                )
            }
            Text(
                text = "Privacy Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Control which online services UnoOne can access. All features default to OFF for your privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Online Bhashini TTS
        PrivacyToggleCard(
            title = "Online Text-to-Speech (Bhashini)",
            description = "Use Bhashini cloud TTS for Indian language voices. Requires internet. Audio data is sent to Bhashini servers.",
            beta = true,
            checked = bhashiniTts,
            onCheckedChange = viewModel::setBhashiniTtsEnabled
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Online RAG web search
        PrivacyToggleCard(
            title = "Online Web Search (RAG)",
            description = "Allow the agent to search the web for information. Search queries are sent to external servers.",
            checked = onlineRag,
            onCheckedChange = viewModel::setOnlineRagEnabled
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Online STT fallback
        PrivacyToggleCard(
            title = "Online Speech Recognition Fallback",
            description = "Use cloud speech recognition when on-device STT fails. Audio data is sent to external servers.",
            checked = onlineSttFallback,
            onCheckedChange = viewModel::setOnlineSttFallbackEnabled
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Analytics sharing
        PrivacyToggleCard(
            title = "Share Usage Analytics",
            description = "Help improve UnoOne by sharing anonymous usage patterns. No personal data or voice recordings are shared.",
            checked = analyticsSharing,
            onCheckedChange = viewModel::setAnalyticsSharingEnabled
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Changes are saved automatically. Offline features always work regardless of these settings.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun PrivacyToggleCard(
    title: String,
    description: String,
    beta: Boolean = false,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (beta) {
                        Text(
                            text = " BETA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}