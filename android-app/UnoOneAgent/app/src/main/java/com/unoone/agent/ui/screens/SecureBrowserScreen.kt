package com.unoone.agent.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unoone.agent.ui.viewmodel.BrowserPromptKind
import com.unoone.agent.ui.viewmodel.SecureBrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UnoOne-controlled WebView running the local Page Agent with Gemma 4 planning. */
@Composable
fun SecureBrowserScreen(
    viewModel: SecureBrowserViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val prompt by viewModel.prompt.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    var url by remember { mutableStateOf(state.currentUrl) }
    var task by remember { mutableStateOf("") }
    var promptText by remember(prompt?.id) { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // WebView file inputs require an Activity-owned result launcher. The controller requests it
    // only after PageAgent's native authorization step, and the selected content URI is returned to
    // the originating <input type="file"> callback.
    val webFileChooserLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        viewModel.completeFileChooser(result.resultCode, result.data)
    }

    DisposableEffect(webFileChooserLauncher) {
        viewModel.setFileChooserLauncher { intent -> webFileChooserLauncher.launch(intent) }
        onDispose { viewModel.setFileChooserLauncher(null) }
    }

    // C9: pick a local/offline HTML form (SAF) and load it into the sandboxed WebView at the synthetic
    // local-form origin so PageAgent can fill it offline. Reading the bytes runs on Dispatchers.IO so
    // the launcher callback never blocks the main thread; the load itself is dispatched back to it.
    val localFormLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val (html, name) = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "local form"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not open the selected form")
                    String(bytes, Charsets.UTF_8) to displayName
                }.getOrElse { e -> "" to (e.message ?: "read error") }
            }
            withContext(Dispatchers.Main) {
                viewModel.loadLocalFormHtml(html, name)
            }
        }
    }

    fun handleBack() {
        if (!viewModel.goBack()) {
            viewModel.closeSession()
            onBack()
        }
    }

    BackHandler { handleBack() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::handleBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("UnoOne Secure Browser", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Gemma 4 + Page Agent · local planning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.error.isBlank()) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(state.phase, fontWeight = FontWeight.Bold)
                Text(state.status, style = MaterialTheme.typography.bodySmall)
                if (state.modelBackend.isNotBlank()) {
                    Text("Gemma backend: ${state.modelBackend}", style = MaterialTheme.typography.labelSmall)
                }
                if (state.error.isNotBlank()) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (state.prototypeSafetyOff) {
                        "⚠ PROTOTYPE MODE: browser action safety is OFF. PageAgent may open any public HTTPS page and submit forms, files, credentials and payment actions without confirmation."
                    } else {
                        "Standard mode: credentials, OTPs, CAPTCHA and legal steps require manual control; payments are blocked; files and final submission require confirmation."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.prototypeSafetyOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(if (state.prototypeSafetyOff) "Public HTTPS URL" else "Approved HTTPS URL") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.navigate(url) }, enabled = state.sessionActive) {
                Text("Go")
            }
        }

        AndroidView(
            factory = {
                WebView(context).also(viewModel::attachWebView)
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                label = { Text("What should UnoOne do on this page?") },
                placeholder = { Text("Fill the profile form and stop before final submission") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Eyes-free (WS4): speak the task instead of typing it. Tap to start, tap again to
                // stop + transcribe + run. RECORD_AUDIO is requested at app startup.
                IconButton(
                    onClick = {
                        if (isListening) viewModel.stopVoiceTask() else viewModel.startVoiceTask(context)
                    },
                    enabled = state.runtimeReady && !state.taskRunning
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop speaking your task" else "Speak your task"
                    )
                }
                OutlinedButton(
                    onClick = viewModel::readPageAloud,
                    enabled = state.runtimeReady && !state.taskRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                    Text("Read Page", modifier = Modifier.padding(start = 6.dp))
                }
                // C9: open an offline .html form from device storage (SAF) and load it into the
                // sandboxed WebView so PageAgent can fill it offline. PageAgent safety gates apply
                // unchanged (payment/credential/OTP/captcha/legal/final-submission → confirm/takeover).
                OutlinedButton(
                    onClick = { localFormLauncher.launch(arrayOf("text/html")) },
                    enabled = state.sessionActive && !state.taskRunning
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Text("Load Form", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.executeTask(task) },
                    enabled = state.runtimeReady && !state.taskRunning && task.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Run", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = viewModel::stopTask,
                    enabled = state.taskRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("Stop", modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (state.lastResult.isNotBlank()) {
                Text(
                    state.lastResult,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    color = Color.Unspecified
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    prompt?.let { current ->
        AlertDialog(
            onDismissRequest = {
                viewModel.respondToPrompt(current.id, approved = false)
            },
            title = {
                Text(
                    when (current.kind) {
                        BrowserPromptKind.CONFIRM -> "Confirm browser action"
                        BrowserPromptKind.ASK -> "PageAgent needs information"
                        BrowserPromptKind.TAKEOVER -> "Manual control required"
                    }
                )
            },
            text = {
                Column {
                    Text(current.message)
                    if (current.kind == BrowserPromptKind.ASK) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            label = { Text("Your answer") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (current.kind == BrowserPromptKind.TAKEOVER) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Complete the required step directly in the page, then tap Done. UnoOne will observe the updated page and continue.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.respondToPrompt(
                            current.id,
                            approved = true,
                            text = promptText
                        )
                    },
                    enabled = current.kind != BrowserPromptKind.ASK || promptText.isNotBlank()
                ) {
                    Text(if (current.kind == BrowserPromptKind.TAKEOVER) "Done" else "Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondToPrompt(current.id, approved = false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
