package com.unoone.agent.owl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.phonecontrol.AppRegistry
import com.unoone.agent.phonecontrol.ScreenshotCapture
import com.unoone.agent.screenshot.ScreenshotPermissionActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class OwlTaskViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as UnoOneApplication
    private val registry = AppRegistry(app)
    val apps = MutableStateFlow<List<AppRegistry.App>>(emptyList())
    val status = MutableStateFlow("")
    init { viewModelScope.launch { apps.value = withContext(Dispatchers.IO) { registry.discover() } } }
    fun permission() = ScreenshotPermissionActivity.launch(application)
    fun practice() = application.startActivity(android.content.Intent(application, OwlPracticeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    fun submit(consent: OwlTaskConsent) {
        if (!ScreenshotCapture.hasPermission()) { status.value = "Screen capture permission required; grant it then approve again."; return }
        viewModelScope.launch {
            // Submit immediately. Automatic practice navigation belongs to the admitted worker,
            // never to this ViewModel (which neither owns the UI lease nor survives Stop).
            status.value = withContext(Dispatchers.IO) { application.taskRuntime.submitOwl(consent, registry).toString() }
        }
    }
}

@Composable
fun OwlTaskScreen(onBack: () -> Unit, model: OwlTaskViewModel = viewModel()) {
    val apps by model.apps.collectAsState(); val status by model.status.collectAsState()
    var pkg by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("4") }; var seconds by remember { mutableStateOf("180") }
    var review by remember { mutableStateOf<OwlTaskConsent?>(null) }
    review?.let { consent -> AlertDialog(onDismissRequest = { review = null },
        title = { Text("Approve this bounded screen task?") },
        text = { Text("Package: ${consent.packageName}\nGoal: ${consent.instruction}\nMaximum ${consent.maxSteps} steps / ${consent.maxSeconds} seconds.\nAllow local full-screen frames and exact native operations for this scope only. No per-frame popup. Native privacy classification is conservative, not a guarantee: passwords, sensitive screens, overlays, multiple windows and unknown canvas stop the task. Never approve on a private screen. Leaving the package stops without reopening it.") },
        confirmButton = { TextButton(onClick = { review = null; model.submit(consent) }) { Text("Approve task") } },
        dismissButton = { TextButton(onClick = { review = null }) { Text("Cancel") } }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("Back to task board") }
        Text("GUI-Owl approved screen tasks", style = MaterialTheme.typography.headlineSmall)
        Text("Experimental. Load GUI-Owl in Models first. Generic chat and browser tasks are unsupported. Only native open/read/find/focus/write/click/select-tab goals have postconditions; unsupported goals need you, never fake completion.")
        Button(onClick = model::practice) { Text("Open Owl practice screen (no capture)") }
        TextButton(onClick = { pkg = "com.unoone.agent"; instruction = "device: click \"Search\" in com.unoone.agent" }) { Text("Select own app practice goal") }
        Text("Choose a currently installed package (no default):")
        apps.forEach { app -> TextButton(onClick = { pkg = app.packageName }) { Text((if (pkg == app.packageName) "Selected: " else "") + app.label + " — " + app.packageName) } }
        Text("Examples: device: open <selected.package> then read screen\ndevice: focus \"Search\" in <selected.package>\ndevice: write \"weather\" into \"Search\" in <selected.package>\ndevice: click \"Search\" in <selected.package>\ndevice: select tab \"Home\" in <selected.package>\ndevice: find \"Exact name\" in <selected.package>\nFind currently requires an exact visible native match. Unknown targets/canvas are excluded.")
        OutlinedTextField(instruction, { instruction = it }, label = { Text("Explicit native goal") })
        OutlinedTextField(steps, { steps = it }, label = { Text("Maximum steps (1–12)") })
        OutlinedTextField(seconds, { seconds = it }, label = { Text("Maximum seconds (10–600)") })
        Button(onClick = model::permission) { Text("Grant Android screen capture permission") }
        Button(onClick = { review = runCatching { OwlTaskConsent(pkg, instruction, steps.toInt(), seconds.toInt()) }.getOrNull() }, enabled = pkg.isNotBlank() && instruction.isNotBlank()) { Text("Review bounded task consent") }
        Text(status)
        Text("Follow results and Stop/Cancel on the task board. Own-app practice opens after a three-second delay only once this task owns the UI; queued tasks do not navigate. This approval is memory-only, is revoked by Stop even while this dialog is pending, and cannot resume after process death.")
    }
}
