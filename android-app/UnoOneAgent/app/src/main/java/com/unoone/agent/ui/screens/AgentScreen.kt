package com.unoone.agent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.unoone.agent.voice.VoiceLanguage
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    voiceLanguage: String = VoiceLanguage.DEFAULT,
    onVoiceLanguageSelected: (String) -> Unit = {},
    onNavigateToSecureBrowser: () -> Unit = {},
    skillCount: Int = 0,
    onNavigateToSkills: () -> Unit = {}
) {
    // 5B: rememberSaveable preserves text across configuration changes (rotation)
    var textInput by rememberSaveable { mutableStateOf("") }
    val timeline by viewModel.timelineSteps.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isBlindAidActive by viewModel.isBlindAidActive.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isHandsFree by viewModel.isHandsFree.collectAsState()
    val offlineMode by viewModel.offlineMode.collectAsState()
    val isAgentEnabled by viewModel.isAgentEnabled.collectAsState()
    var showDisabledDialog by remember { mutableStateOf(false) }
    // C8: loaded document + loading flag.
    val loadedDocument by viewModel.loadedDocument.collectAsState()
    val isLoadingDocument by viewModel.isLoadingDocument.collectAsState()
    val editableDocument by viewModel.editableDocument.collectAsState()
    val isFillingDocument by viewModel.isFillingDocument.collectAsState()
    val documentFillMessage by viewModel.documentFillMessage.collectAsState()
    val documentFillPickerRequest by viewModel.documentFillPickerRequest.collectAsState()
    val context = LocalContext.current

    // Keep the offline-mode chip live: VoiceModule exposes @Volatile state (not a Flow), so poll
    // it on a cadence and on first composition.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshOfflineMode()
            kotlinx.coroutines.delay(2000)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening(context)
        }
    }

    // Blind Aid is a direct UI toggle (not the "activate blind aid" text command through the agent
    // pipeline). BlindAidManager is a pure camera path, so it needs only CAMERA — request it here,
    // then activate on grant. Avoids the misleading "needs system access" prompt the vestigial
    // Accessibility gate produced (the gate itself is removed in ToolPermissionRegistry).
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.setBlindAidActive(true)
        }
    }

    // C8: Storage Access Framework picker — load a PDF / image / Excel / HTML / text / CSV file for
    // the on-device brain to read and work on. OpenDocument grants a temporary read grant on the
    // returned content:// uri; we resolve the mime from the ContentResolver and hand both to the
    // ViewModel's DocumentLoader (real parsers, no dummies).
    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            viewModel.loadDocument(context, uri, mime)
        }
    }

    // Offline Document Agent input/output pickers. OpenDocument is read-only; CreateDocument
    // always asks for a new destination, so the original PDF/DOCX is never overwritten.
    val documentFillPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            viewModel.inspectDocumentForFilling(uri, mime)
        }
    }
    var pendingDocumentValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val pdfOutputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.unoone.agent.phonecontrol.document.DocumentFillEngine.PDF_MIME)
    ) { uri -> if (uri != null) viewModel.fillDocumentCopy(uri, pendingDocumentValues) }
    val docxOutputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.unoone.agent.phonecontrol.document.DocumentFillEngine.DOCX_MIME)
    ) { uri -> if (uri != null) viewModel.fillDocumentCopy(uri, pendingDocumentValues) }

    LaunchedEffect(documentFillPickerRequest) {
        val format = documentFillPickerRequest ?: return@LaunchedEffect
        val types = if (format == "docx") {
            arrayOf(com.unoone.agent.phonecontrol.document.DocumentFillEngine.DOCX_MIME)
        } else {
            arrayOf(com.unoone.agent.phonecontrol.document.DocumentFillEngine.PDF_MIME)
        }
        viewModel.consumeDocumentFillPickerRequest()
        documentFillPickerLauncher.launch(types)
    }

    // C8: the question/instruction the user wants the brain to answer about the loaded document.
    var documentQuestion by rememberSaveable { mutableStateOf("") }

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

    if (showDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showDisabledDialog = false },
            title = { Text("UnoOne is disabled") },
            text = { Text("Enable UnoOne before listening, observing, processing, speaking or automating.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.enableAgent()
                    showDisabledDialog = false
                }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showDisabledDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        if (!isAgentEnabled) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = "UnoOne is disabled. All agent activity is inactive."
                    }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "UnoOne is disabled",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Mic · Speech · TTS · Model · Accessibility · Browser · Network: inactive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = viewModel::enableAgent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) { Text("Enable UnoOne") }
                }
            }
        }

        // Offline badge — dynamic: OFFLINE (green) / LIMITED (amber) / NO MODEL (red)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (badgeText, badgeColor) = if (!isAgentEnabled) {
                "APP OFF" to FailedRed
            } else if (isBlindAidActive) {
                // Blind Aid owns the device's memory budget and deliberately unloads Gemma while
                // CameraX + the offline detector are active. That is a healthy exclusive mode, not
                // a degraded/system-fallback state, so do not mislabel it as LIMITED.
                "OFFLINE" to DoneGreen
            } else when (offlineMode) {
                com.unoone.agent.ui.viewmodel.OfflineMode.OFFLINE -> "OFFLINE" to DoneGreen
                com.unoone.agent.ui.viewmodel.OfflineMode.LIMITED -> "LIMITED" to SafetyOrange
                com.unoone.agent.ui.viewmodel.OfflineMode.NO_MODEL -> "NO MODEL" to FailedRed
            }
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelLarge,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isBlindAidActive) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SafetyOrange.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Blind Aid Active",
                        style = MaterialTheme.typography.labelLarge,
                        color = SafetyOrange,
                        fontWeight = FontWeight.Bold,
                        // Eyes-free (WS6): announce when Blind Aid turns on/off. Live region so
                        // TalkBack speaks the change without the user touching the badge.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            // C3: ALWAYS-ENABLED Stop/Cancel. The single guarantee a blind user is never trapped in a
            // stuck "Reading screen" / processing state — one tap cancels the in-flight command,
            // clears the pending permission command, releases the lock, and speaks "Stopped." It is
            // never disabled by isProcessing (the whole point), and carries a full TalkBack label.
            Surface(
                onClick = {
                    if (isBlindAidActive) viewModel.setBlindAidActive(false)
                    else viewModel.cancelCommand()
                },
                enabled = true,
                shape = CircleShape,
                color = FailedRed.copy(alpha = if (isProcessing) 0.85f else 0.15f),
                contentColor = if (isProcessing) Color.White else FailedRed,
                modifier = Modifier
                    .size(44.dp)
                    .semantics {
                        contentDescription = if (isBlindAidActive) "Stop Blind Aid" else "Stop and cancel"
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Blind Aid is a camera-first mode. Do not bury its live boxes below the normal landing
        // controls: keep the status header and give the preview almost the entire visible panel.
        if (isBlindAidActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(620.dp)
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                BlindAidCameraPreview(
                    voiceModule = viewModel.voiceModuleInstance,
                    onClose = { viewModel.setBlindAidActive(false) }
                )
            }
        } else {
        // Keep the agent's work above the controls. The latest step is always visible, expansion is
        // bounded so it cannot push the whole command surface off-screen, and a running task opens
        // the panel automatically. Skills are reachable here because this is where users look to
        // understand which local routine is executing.
        AgentWorkPanel(
            timeline = timeline,
            isProcessing = isProcessing,
            skillCount = skillCount,
            onNavigateToSkills = onNavigateToSkills
        )

        VoiceLanguageQuickSwitcher(
            selectedCode = voiceLanguage,
            enabled = !isListening && !isProcessing,
            onSelected = onVoiceLanguageSelected
        )

        // Eyes-free (WS5): large, TalkBack-labeled capability surface. The four primary actions a
        // blind user reaches in one tap from the top of the screen — Listen (speak a command), Blind
        // Aid (camera guidance), Read Screen (speak what's on screen), Secure Browser. The mic FAB,
        // text input, and quick actions below remain for sighted/quick use.
        CapabilitySurface(
            isProcessing = isProcessing,
            isListening = isListening,
            isHandsFree = isHandsFree,
            isBlindAidActive = isBlindAidActive,
            onListen = {
                if (!isAgentEnabled) {
                    showDisabledDialog = true
                } else {
                // C3/C5: the big LISTEN button is the always-listening hands-free session toggle.
                // During an in-flight command it instead CANCELS (never bricked — a blind user can
                // always interrupt). One tap starts the session: speak → voice reply → re-listen
                // automatically, no repeated tapping. A second tap (or "stop listening") ends it.
                if (isHandsFree) {
                    viewModel.stopHandsFreeSession()
                } else if (isProcessing) {
                    viewModel.cancelCommand()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.toggleHandsFreeSession(context)
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                }
            },
            onBlindAid = {
                if (!isAgentEnabled) {
                    showDisabledDialog = true
                } else if (isBlindAidActive) {
                    viewModel.setBlindAidActive(false)
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.setBlindAidActive(true)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onReadScreen = {
                // C4: MediaProjection + on-device OCR path — in-app one-tap consent, no bounce to MIUI
                // Accessibility settings, and the result is SPOKEN (eyes-free). The Accessibility-based
                // read_screen tool stays for cross-app reading when Accessibility is enabled.
                if (!isAgentEnabled) showDisabledDialog = true
                else viewModel.readScreenViaMediaProjection(context)
            },
            onSecureBrowser = {
                if (!isAgentEnabled) showDisabledDialog = true
                else onNavigateToSecureBrowser()
            }
        )
        }

        // Progress indicator — 5C: derivedStateOf avoids recomputing every frame
        val progress by remember {
            derivedStateOf {
                if (isProcessing) {
                    // Don't assume exactly 7 steps — scale to AgentStatus.DONE as the terminal state
                    (timeline.size.toFloat() / AgentStatus.entries.size).coerceIn(0f, 1f)
                } else 0f
            }
        }
        if (isProcessing) {
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
            isActive = isListening,
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
                    when {
                        !isAgentEnabled -> showDisabledDialog = true
                        // C3: during an in-flight command the mic FAB cancels (never bricked).
                        isProcessing -> viewModel.cancelCommand()
                        // C5: ends the hands-free session if active.
                        isHandsFree -> viewModel.stopHandsFreeSession()
                        isListening -> viewModel.stopListening()
                        else -> {
                            // One-shot push-to-talk for sighted/quick use (the hands-free session is
                            // the primary path via the big LISTEN button). Check permission first.
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startListening(context)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                },
                shape = CircleShape,
                containerColor = if (isListening || isHandsFree) ListeningRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            ) {
                if (isProcessing && !isListening && !isHandsFree) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        imageVector = if (isListening || isHandsFree || isProcessing) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening || isHandsFree || isProcessing) "Stop" else "Speak",
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
                // Eyes-free (WS6): a real floating label (not just a placeholder) so TalkBack
                // announces "Command" when focus lands on the field.
                label = { Text("Command") },
                placeholder = { Text("Type a command...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !isProcessing
            )
            Button(
                onClick = {
                    if (!isAgentEnabled) {
                        showDisabledDialog = true
                    } else if (textInput.isNotBlank()) {
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
                if (!isAgentEnabled) showDisabledDialog = true else viewModel.onQuickAction("Create Note")
            }
            QuickActionButton("Open Chrome", Icons.Default.OpenInBrowser, enabled = !isProcessing) {
                if (!isAgentEnabled) showDisabledDialog = true else viewModel.onQuickAction("Open Chrome")
            }
            QuickActionButton("Calendar", Icons.Default.CalendarMonth, enabled = !isProcessing) {
                if (!isAgentEnabled) showDisabledDialog = true else viewModel.onQuickAction("Calendar")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // C8: load a document (PDF / image / Excel / HTML / text / CSV) for the brain to read + work
        // on. Tapping opens the system file picker; the extracted text is fed as context to the brain.
        Button(
            onClick = {
                if (!isAgentEnabled) {
                    showDisabledDialog = true
                } else documentPickerLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "image/*",
                        "text/plain",
                        "text/html",
                        "text/csv",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel"
                    )
                )
            },
            enabled = !isProcessing && !isLoadingDocument,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Load a document for the AI to read. Opens the file picker." }
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(if (isLoadingDocument) "Reading document…" else "Load Document (PDF / DOCX / Excel / image / text)")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                documentFillPickerLauncher.launch(
                    arrayOf(
                        com.unoone.agent.phonecontrol.document.DocumentFillEngine.PDF_MIME,
                        com.unoone.agent.phonecontrol.document.DocumentFillEngine.DOCX_MIME
                    )
                )
            },
            enabled = !isFillingDocument && !isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Description, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(if (isFillingDocument) "Working on document…" else "Fill PDF / DOCX Offline")
        }

        editableDocument?.let { template ->
            val fillValues = remember(template) {
                mutableStateMapOf<String, String>().apply {
                    template.fields.forEach { field -> put(field.id, field.currentValue) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offline Document Agent", fontWeight = FontWeight.Bold)
                            Text(template.displayName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${template.fields.size} field(s) · saves a verified new copy",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = viewModel::clearEditableDocument) {
                            Icon(Icons.Default.Close, contentDescription = "Close Document Agent")
                        }
                    }
                    template.fields.forEach { field ->
                        when (field.type) {
                            com.unoone.agent.phonecontrol.document.DocumentFieldType.BOOLEAN -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = fillValues[field.id].orEmpty().lowercase() in
                                            setOf("true", "yes", "1", "on", "checked"),
                                        onCheckedChange = { fillValues[field.id] = it.toString() },
                                        enabled = !isFillingDocument
                                    )
                                    Text(field.label)
                                }
                            }
                            else -> OutlinedTextField(
                                value = fillValues[field.id].orEmpty(),
                                onValueChange = { fillValues[field.id] = it },
                                label = { Text(field.label) },
                                supportingText = if (field.options.isNotEmpty()) {
                                    { Text("Options: ${field.options.joinToString().take(180)}") }
                                } else null,
                                enabled = !isFillingDocument,
                                singleLine = field.type != com.unoone.agent.phonecontrol.document.DocumentFieldType.TEXT,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Button(
                        onClick = {
                            pendingDocumentValues = fillValues.toMap()
                            val name = template.displayName
                            val dot = name.lastIndexOf('.')
                            val completed = if (dot > 0) {
                                "${name.substring(0, dot)}-completed${name.substring(dot)}"
                            } else "$name-completed"
                            if (template.kind == com.unoone.agent.phonecontrol.document.DocumentFillKind.FILLABLE_PDF) {
                                pdfOutputLauncher.launch(completed)
                            } else {
                                docxOutputLauncher.launch(completed)
                            }
                        },
                        enabled = !isFillingDocument && fillValues.values.any { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Verified Copy")
                    }
                    if (documentFillMessage.isNotBlank()) {
                        Text(
                            documentFillMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
        }

        // C8: loaded-document card — shows what was loaded and lets the user ask the brain about it.
        loadedDocument?.let { doc ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = doc.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            viewModel.clearDocument()
                            documentQuestion = ""
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove loaded document"
                            )
                        }
                    }
                    val kindWord = when (doc.kind) {
                        com.unoone.agent.core.document.DocKind.PDF -> "PDF, ${doc.pagesOrSheets} page(s)"
                        com.unoone.agent.core.document.DocKind.IMAGE -> "image (OCR)"
                        com.unoone.agent.core.document.DocKind.XLSX -> "Excel spreadsheet"
                        com.unoone.agent.core.document.DocKind.DOCX -> "Word document"
                        com.unoone.agent.core.document.DocKind.HTML -> "web page"
                        com.unoone.agent.core.document.DocKind.CSV -> "CSV"
                        else -> "text"
                    }
                    Text(
                        text = "$kindWord · ${doc.text.length} characters" +
                            if (doc.truncated) " · first part only" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = documentQuestion,
                            onValueChange = { documentQuestion = it },
                            label = { Text("Ask about this document") },
                            placeholder = { Text("Summarize this document") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !isProcessing
                        )
                        Button(
                            onClick = {
                                viewModel.askAboutDocument(documentQuestion)
                                documentQuestion = ""
                            },
                            enabled = !isProcessing
                        ) {
                            Text("Ask")
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun AgentWorkPanel(
    timeline: List<TimelineStep>,
    isProcessing: Boolean,
    skillCount: Int,
    onNavigateToSkills: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val latest = timeline.lastOrNull()

    LaunchedEffect(isProcessing) {
        if (isProcessing) expanded = true
    }
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) runCatching { listState.animateScrollToItem(timeline.lastIndex) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription = "Agent activity, ${if (expanded) "expanded" else "collapsed"}. " +
                    (latest?.let { "Latest: ${it.label}." } ?: "Ready.")
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Agent activity", fontWeight = FontWeight.SemiBold)
                    val summary = latest?.let {
                        if (it.detail.isBlank()) it.label else "${it.label} — ${it.detail}"
                    } ?: "Ready — waiting for your command"
                    Text(
                        text = "Now: $summary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse agent activity" else "Expand agent activity"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (isProcessing) "Working locally" else "Runs locally and follows the safety level",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isProcessing) ExecutingCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onNavigateToSkills) {
                    Text("Skills ($skillCount)")
                }
            }

            AnimatedVisibility(visible = expanded) {
                if (timeline.isEmpty()) {
                    Text(
                        "Steps will appear here while the agent understands, checks safety, executes, and verifies.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((timeline.size * 72).coerceIn(72, 190).dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(timeline) { index, step ->
                            TimelineStepCard(step, isLatest = index == timeline.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlindAidCameraPreview(
    voiceModule: com.unoone.agent.voice.VoiceModule,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val narrationScope = rememberCoroutineScope()
    val previewSessionActive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val previewViewRef = remember {
        java.util.concurrent.atomic.AtomicReference<PreviewView?>(null)
    }
    val boundUseCasesRef = remember {
        java.util.concurrent.atomic.AtomicReference<List<androidx.camera.core.UseCase>>(emptyList())
    }
    val narrationJob = remember {
        java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    }
    // C2: "warming up" state shown until the camera provider is ready and bound off the main thread.
    var cameraBound by remember { mutableStateOf(false) }

    // BlindAidManager is eagerly created via remember — it only runs while the camera-first Blind
    // Aid panel is visible. The MediaPipe detector itself is lazy (created on the
    // first analyzed frame) so construction never blocks activation.
    val blindAidManager = remember {
        com.unoone.agent.phonecontrol.BlindAidManager(
            context = context,
            onFeedbackSpoken = { feedback ->
                val next = narrationScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    voiceModule.speakAwait(feedback)
                }
                narrationJob.getAndSet(next)?.cancel()
                // A changed, freshly-confirmed scene replaces any older spoken observation.
                voiceModule.stopSpeaking()
                next.start()
            },
            languageCodeProvider = {
                VoiceLanguage.normalize(
                    context.getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)
                        .getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
                )
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            previewSessionActive.set(false)
            narrationJob.getAndSet(null)?.cancel()
            // Coroutine cancellation stops the await, but AudioTrack must also be flushed so no
            // cached Blind Aid utterance survives after the camera panel closes.
            voiceModule.stopSpeaking()
            previewViewRef.getAndSet(null)?.previewStreamState?.removeObservers(lifecycleOwner)
            // Unbind only this preview session. unbindAll() is process-wide and allowed a stale
            // Activity/composition to tear down a newer Blind Aid camera on Xiaomi.
            try {
                val useCases = boundUseCasesRef.getAndSet(emptyList())
                if (useCases.isNotEmpty() && cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbind(*useCases.toTypedArray())
                }
            } catch (e: Exception) {
                com.unoone.agent.core.util.Logger.e("BlindAidCameraPreview: Camera unbind failed", e)
            }
            blindAidManager.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView-backed preview composes correctly with status/close controls.
                    // SurfaceView mode can consume overlay/accessibility touches on Xiaomi builds.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                previewViewRef.set(previewView)
                val streamObserver = androidx.lifecycle.Observer<PreviewView.StreamState> { state ->
                    if (previewSessionActive.get()) {
                        cameraBound = state == PreviewView.StreamState.STREAMING
                    }
                }
                previewView.previewStreamState.observe(lifecycleOwner, streamObserver)

                // C2: bind the camera ASYNCHRONOUSLY off the main thread. The prior
                // cameraProviderFuture.get() blocked the AndroidView factory on the main thread,
                // janking/freezing Blind Aid activation for seconds (worse under memory pressure).
                // Build the PreviewView immediately so a surface exists, then bind when the future
                // completes on the main executor.
                cameraProviderFuture.addListener({
                    if (!previewSessionActive.get()) return@addListener
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build().also { analysis ->
                                analysis.setAnalyzer(
                                    blindAidManager.analyzerExecutor,
                                    blindAidManager.getAnalyzer()
                                )
                            }
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        boundUseCasesRef.set(listOf(preview, imageAnalysis))
                    } catch (e: Exception) {
                        cameraBound = false
                        com.unoone.agent.core.util.Logger.e("BlindAidCameraPreview: Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(context))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // C2: brief "warming up" overlay until the camera is bound (non-frozen, immediate feedback).
        if (!cameraBound) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Warming up the camera…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Blind Aid warming up the camera"
                        }
                    )
                }
            }
        }

        // Live bounding-box overlay — draws labeled MediaPipe detections over the preview. Boxes are
        // normalized to the upright image; FILL_CENTER maps them into this view (center-crop).
        val overlay by blindAidManager.overlay.collectAsState()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxes = overlay.boxes
            if (boxes.isEmpty()) return@Canvas
            val viewW = size.width
            val viewH = size.height
            val ar = overlay.aspectRatio
            if (ar <= 0f) return@Canvas
            val scale = maxOf(viewW / ar, viewH)
            val scaledW = ar * scale
            val scaledH = scale
            val offsetX = (viewW - scaledW) / 2f
            val offsetY = (viewH - scaledH) / 2f
            val boxPaint = android.graphics.Paint().apply {
                color = 0xFFFF6B00.toInt() // SafetyOrange
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f
                isAntiAlias = true
            }
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 30f
                isAntiAlias = true
                setShadowLayer(6f, 1f, 1f, android.graphics.Color.BLACK)
            }
            drawIntoCanvas { canvas ->
                for (box in boxes) {
                    val left = offsetX + box.rect.left * scaledW
                    val top = offsetY + box.rect.top * scaledH
                    val right = offsetX + box.rect.right * scaledW
                    val bottom = offsetY + box.rect.bottom * scaledH
                    canvas.nativeCanvas.drawRect(left, top, right, bottom, boxPaint)
                    if (box.label.isNotBlank()) {
                        canvas.nativeCanvas.drawText(box.label, left, (top - 8f).coerceAtLeast(labelPaint.textSize), labelPaint)
                    }
                }
            }
        }

        if (cameraBound) {
            val detectionText = if (overlay.boxes.isEmpty()) {
                "Scanning • no object detected yet"
            } else {
                "Detected: " + overlay.boxes.map { it.label }.distinct().joinToString()
            }
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = detectionText
                    }
            ) {
                Text(
                    text = detectionText,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Overlay Close Button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .size(36.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close Scanning", tint = Color.White)
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

/** Primary-screen selector for the active offline STT/TTS language. */
@Composable
private fun VoiceLanguageQuickSwitcher(
    selectedCode: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = VoiceLanguage.SUPPORTED.firstOrNull { it.code == selectedCode }
        ?: VoiceLanguage.SUPPORTED.first()
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Voice language: ${selected.display}. Double tap to change."
                }
        ) {
            Icon(Icons.Default.Language, contentDescription = null)
            Text("Voice: ${selected.display}", modifier = Modifier.padding(horizontal = 8.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            VoiceLanguage.SUPPORTED.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.display) },
                    onClick = {
                        expanded = false
                        if (language.code != selected.code) onSelected(language.code)
                    }
                )
            }
        }
    }
}

/**
 * Eyes-free (WS5): the 2x2 large-button capability surface at the top of the Agent screen. Each
 * button is a TalkBack-live-region with a full spoken label ([Capability.talkBackLabel]) so a blind
 * user hears the action before tapping. Routing is pure ([Capability]/[CapabilityHandler], JVM-tested
 * in [CapabilityTest]); the live tap + TalkBack announcement are device-time gates.
 */
@Composable
private fun CapabilitySurface(
    isProcessing: Boolean,
    isListening: Boolean,
    isHandsFree: Boolean,
    isBlindAidActive: Boolean,
    onListen: () -> Unit,
    onBlindAid: () -> Unit,
    onReadScreen: () -> Unit,
    onSecureBrowser: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // C3/C5: LISTEN is ALWAYS enabled. During an in-flight command it acts as Cancel
            // (interrupt); during a hands-free session it shows "Stop listening". Never gated by
            // isProcessing — a blind user can always interrupt/stop from the primary action.
            CapabilityButton(
                capability = Capability.LISTEN,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onListen,
                labelOverride = if (isHandsFree) "Stop listening" else if (isProcessing) "Stop" else null,
                iconOverride = if (isHandsFree || isProcessing) Icons.Default.Stop else null,
                talkBackOverride = when {
                    isHandsFree -> "Stop listening. Ends the hands-free session."
                    isProcessing -> "Stop. Cancels the current command."
                    else -> null
                }
            )
            CapabilityButton(
                capability = Capability.BLIND_AID,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                onClick = onBlindAid
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapabilityButton(
                capability = Capability.READ_SCREEN,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f),
                onClick = onReadScreen
            )
            CapabilityButton(
                capability = Capability.SECURE_BROWSER,
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onSecureBrowser
            )
        }
    }
}

@Composable
private fun CapabilityButton(
    capability: Capability,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    // C3/C5: optional overrides so the LISTEN button can show "Stop"/"Stop listening" with a Stop
    // icon and a matching TalkBack label when it doubles as the Cancel/hands-free-stop control.
    labelOverride: String? = null,
    iconOverride: ImageVector? = null,
    talkBackOverride: String? = null
) {
    val label = labelOverride ?: capability.label
    val icon = iconOverride ?: capabilityIcon(capability)
    val talkBack = talkBackOverride ?: capability.talkBackLabel
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.12f else 0.05f),
        contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        modifier = modifier
            .height(96.dp)
            .semantics { contentDescription = talkBack }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // the surface's contentDescription carries the full label
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun capabilityIcon(capability: Capability): ImageVector = when (capability) {
    Capability.LISTEN -> Icons.Default.Mic
    Capability.BLIND_AID -> Icons.Default.SmartToy
    Capability.READ_SCREEN -> Icons.AutoMirrored.Filled.VolumeUp
    Capability.SECURE_BROWSER -> Icons.Default.OpenInBrowser
}

@Composable
private fun TimelineStepCard(step: TimelineStep, isLatest: Boolean = false) {
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
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isLatest) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
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
