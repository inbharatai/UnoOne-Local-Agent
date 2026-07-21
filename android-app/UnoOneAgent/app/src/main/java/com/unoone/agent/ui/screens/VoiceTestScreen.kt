package com.unoone.agent.ui.screens

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.ui.theme.DoneGreen
import com.unoone.agent.ui.theme.FailedRed
import com.unoone.agent.ui.theme.SafetyOrange
import com.unoone.agent.ui.viewmodel.VoiceTestViewModel
import com.unoone.agent.voice.VoiceRuntimeState
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceAgentRuntime

/**
 * STT/TTS test screen: verifies offline voice works before relying on it. Records 3s → transcribes
 * via Sherpa → shows text + confidence; types text → speaks via the active TTS engine. Shows which
 * engine is active so the user knows whether they are truly offline (Sherpa) or on the emergency
 * system fallback. Reached from Settings.
 */
@Composable
fun VoiceTestScreen(viewModel: VoiceTestViewModel, onBack: () -> Unit) {
    val engine by viewModel.engine.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val confidence by viewModel.confidence.collectAsState()
    val wakeMatch by viewModel.wakeMatch.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val diagnostics by VoiceAgentRuntime.diagnostics.collectAsState()
    var ttsText by remember { mutableStateOf(VoiceLanguage.testPhrase(engine.language)) }

    LaunchedEffect(engine.language) {
        ttsText = VoiceLanguage.testPhrase(engine.language)
    }

    // Poll the @Volatile engine state on a 1s cadence — VoiceModule does not expose a Flow.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            kotlinx.coroutines.delay(1000)
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
                text = "Voice Test",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Engine status
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Active Engines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                EngineLine("STT", engine.stt, engine.sttReady)
                EngineLine("TTS", engine.tts, engine.ttsReady)
                Text(
                    "Language: ${VoiceLanguage.displayName(engine.language)} (${VoiceLanguage.localeTag(engine.language)})",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "System STT fallback: ${if (engine.systemFallbackAllowed) "allowed" else "off (offline-first)"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isDebuggable) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Developer Voice Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Debug build only. Transcript fields are memory-only and clear when UnoOne is disabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticLine("Agent enabled", AgentRuntimeGate.isEnabled().toString())
                    DiagnosticLine("State", diagnostics.state.name)
                    DiagnosticLine(
                        "Microphone permission",
                        permissionState(context, Manifest.permission.RECORD_AUDIO)
                    )
                    DiagnosticLine(
                        "Accessibility",
                        if (UnoOneAccessibilityService.isEnabled()) "enabled" else "off"
                    )
                    DiagnosticLine(
                        "Calendar read permission",
                        permissionState(context, Manifest.permission.READ_CALENDAR)
                    )
                    DiagnosticLine("Contacts", "not requested; name lookup is unavailable")
                    DiagnosticLine("Selected language", diagnostics.preferredReplyLanguage)
                    DiagnosticLine("Raw/final transcript", diagnostics.finalTranscript)
                    DiagnosticLine("Stable partial", diagnostics.stablePartialTranscript)
                    DiagnosticLine("Normalised", diagnostics.normalizedTranscript)
                    DiagnosticLine("Wake phrase", diagnostics.wakePhrase)
                    DiagnosticLine(
                        "Wake confidence",
                        "${(diagnostics.wakeConfidence * 100).toInt()}%"
                    )
                    DiagnosticLine("Extracted command", diagnostics.extractedCommand)
                    DiagnosticLine("Parsed intent", diagnostics.parsedIntent)
                    DiagnosticLine(
                        "Intent confidence",
                        "${(diagnostics.intentConfidence * 100).toInt()}%"
                    )
                    DiagnosticLine("Action result", diagnostics.actionResult)
                    DiagnosticLine("Verification", diagnostics.verificationResult)
                    DiagnosticLine("Error", diagnostics.errorCode)
                    DiagnosticLine("Recovery", diagnostics.recoveryAction)
                    DiagnosticLine("Transition", diagnostics.transitionReason)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // STT test
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Speech Recognition", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.startSttTest(context) },
                    enabled = !isRecording,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Record 3s & Transcribe")
                }
                if (isRecording) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Text(" Listening…", modifier = Modifier.padding(start = 8.dp))
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = viewModel::cancelRecording) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Stop")
                        }
                    }
                }
                if (transcript.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Transcript", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(transcript, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val pct = (confidence * 100).toInt()
                    Text(
                        "Confidence: $pct%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (confidence >= 0.6f) DoneGreen else if (confidence > 0f) SafetyOrange else FailedRed
                    )
                    Text(
                        wakeMatch,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (wakeMatch.startsWith("Wake matched")) DoneGreen else SafetyOrange
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TTS test
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Text-to-Speech", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ttsText,
                    onValueChange = { ttsText = it },
                    label = { Text("Text to speak") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.speak(ttsText) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Speak")
                    }
                    OutlinedButton(onClick = viewModel::stopSpeaking, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Stop")
                    }
                }
            }
        }

        message?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EngineLine(label: String, state: VoiceRuntimeState, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        val (text, color) = when (state) {
            VoiceRuntimeState.SHERPA -> "Sherpa (offline)" to DoneGreen
            VoiceRuntimeState.SYSTEM_FALLBACK -> "System fallback" to SafetyOrange
            VoiceRuntimeState.UNAVAILABLE -> "Unavailable" to FailedRed
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f))
    }
}

private fun permissionState(context: android.content.Context, permission: String): String =
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        "granted"
    } else {
        "denied"
    }
