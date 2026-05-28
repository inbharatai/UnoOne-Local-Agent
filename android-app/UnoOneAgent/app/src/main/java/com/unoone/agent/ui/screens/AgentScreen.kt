package com.unoone.agent.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.ui.components.ConfirmationDialog
import com.unoone.agent.ui.components.ConfirmationLevel
import com.unoone.agent.ui.components.WaveformVisualizer
import com.unoone.agent.ui.theme.DoneGreen
import com.unoone.agent.ui.theme.ExecutingCyan
import com.unoone.agent.ui.theme.FailedRed
import com.unoone.agent.ui.theme.ListeningRed
import com.unoone.agent.ui.theme.SafetyOrange
import com.unoone.agent.ui.theme.SpeakingGreen
import com.unoone.agent.ui.theme.TranscribingYellow
import com.unoone.agent.ui.theme.UnderstandingBlue
import com.unoone.agent.ui.theme.VerifyingTeal
import com.unoone.agent.ui.viewmodel.AgentViewModel

@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    var textInput by remember { mutableStateOf("") }
    val timeline by viewModel.timelineSteps.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val context = LocalContext.current

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening(context)
        }
    }

    // Confirmation dialog
    pendingConfirmation?.let { (message, level) ->
        ConfirmationDialog(
            message = message,
            level = level,
            onResult = { allowed ->
                viewModel.respondToConfirmation(allowed)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "UnoOne",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "One private AI agent for every phone action.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        // Offline badge
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Offline Local",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Progress indicator
        if (isProcessing) {
            val progress = calculateProgress(timeline)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Waveform visualizer
        WaveformVisualizer(
            amplitude = amplitude,
            isActive = viewModel.isListening,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Mic button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = {
                    if (viewModel.isListening) {
                        viewModel.stopListening()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                shape = CircleShape,
                containerColor = if (viewModel.isListening) ListeningRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            ) {
                if (isProcessing && !viewModel.isListening) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        imageVector = if (viewModel.isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (viewModel.isListening) "Stop" else "Speak",
                        modifier = Modifier.size(36.dp),
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Text input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Type a command...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !isProcessing
            )
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.onTextCommand(textInput)
                        textInput = ""
                    }
                },
                enabled = !isProcessing
            ) {
                Text("Go")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickActionButton("Create Note", Icons.Default.EditNote, enabled = !isProcessing) {
                viewModel.onQuickAction("Create Note")
            }
            QuickActionButton("Open Chrome", Icons.Default.OpenInBrowser, enabled = !isProcessing) {
                viewModel.onQuickAction("Open Chrome")
            }
            QuickActionButton("Calendar", Icons.Default.CalendarMonth, enabled = !isProcessing) {
                viewModel.onQuickAction("Calendar")
            }
            QuickActionButton("Open App", Icons.Default.Language, enabled = !isProcessing) {
                viewModel.onQuickAction("Open App")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Agent Flow Timeline
        Text(
            text = "Agent Flow Timeline",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(timeline) { step ->
                TimelineStepCard(step)
            }
        }
    }
}

private fun calculateProgress(steps: List<TimelineStep>): Float {
    val totalSteps = 7 // UNDERSTANDING, TOOL_SELECTED, SAFETY_CHECK, EXECUTING, VERIFYING, SPEAKING, DONE
    return (steps.size.toFloat() / totalSteps).coerceIn(0f, 1f)
}

@Composable
private fun QuickActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TimelineStepCard(step: TimelineStep) {
    val color = when (step.status) {
        AgentStatus.LISTENING -> ListeningRed
        AgentStatus.TRANSCRIBING -> TranscribingYellow
        AgentStatus.UNDERSTANDING -> UnderstandingBlue
        AgentStatus.TOOL_SELECTED -> MaterialTheme.colorScheme.tertiary
        AgentStatus.SAFETY_CHECK -> SafetyOrange
        AgentStatus.EXECUTING -> ExecutingCyan
        AgentStatus.VERIFYING -> VerifyingTeal
        AgentStatus.SPEAKING -> SpeakingGreen
        AgentStatus.DONE -> DoneGreen
        AgentStatus.FAILED -> FailedRed
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Pulsing animation for executing steps
    val isExecuting = step.status == AgentStatus.EXECUTING
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_${step.timestampMs}")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val animatedColor by animateColorAsState(
        targetValue = if (isExecuting) color.copy(alpha = pulseAlpha) else color,
        label = "step_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = animatedColor.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(animatedColor)
            )
            Column {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = animatedColor
                )
                AnimatedVisibility(visible = step.detail.isNotBlank()) {
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}