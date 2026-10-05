package com.unoone.agent.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.device.UiStateHasher
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.phonecontrol.ScreenshotCapture
import com.unoone.agent.ui.viewmodel.SettingsViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Developer-only opt-in UI. The optional interpreter must be explicitly supplied by its owner. */
@Composable
fun DeveloperDiagnosticsScreen(
    viewModel: SettingsViewModel,
    onClose: () -> Unit,
    interpretImage: (suspend (com.unoone.agent.core.device.PerceptionState, com.unoone.agent.localbrain.SnapshotImageEnvelope, String) -> String)? = null
) {
    val context = LocalContext.current
    val enabled by viewModel.isAgentEnabled.collectAsState()
    val safety by viewModel.securityLevel.collectAsState()
    val scope = rememberCoroutineScope()
    val session = remember { V3PerceptionSession(context.applicationContext) }
    var frame by remember { mutableStateOf<V3PerceptionSession.Frame?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("No capture requested") }
    var interpretation by remember { mutableStateOf<String?>(null) }
    var resultBox by remember { mutableStateOf<com.unoone.agent.localbrain.GroundingBox?>(null) }
    var approved by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("Describe this captured screen. Give advice only; do not perform actions.") }
    var revision by remember { mutableIntStateOf(0) }
    val app = context.applicationContext as? UnoOneApplication
    val service = UnoOneAccessibilityService.getInstance()
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    fun clear() {
        generation++
        approved = false; resultBox = null
        captureJob?.cancel(); captureJob = null
        frame?.close(); frame = null; interpretation = null; busy = false
    }
    BackHandler { clear(); onClose() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) clear()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); clear() }
    }
    LaunchedEffect(enabled) { if (!enabled) clear() }
    LaunchedEffect(frame) { if (frame != null) { delay(30_000); status = "Captured screen is stale; advice only, no actions" } }
    val backend = app?.orchestrator?.loadedBrainBackend()?.takeIf { it.isNotBlank() } ?: "Unavailable"
    val profile = app?.orchestrator?.loadedBrainProfile()?.displayName ?: "Unavailable"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text("Local, opt-in inspection. No automatic chat capture. Analysis admission expires after 30 seconds; closing discards pixels. Export never includes pixels, OCR text, node text, app identity or screen hashes.")
        TextButton(onClick = { clear(); onClose() }) { Text("Close diagnostics") }
        Text("Runtime master: $enabled\nActive profile: $profile\nArtifact hash: Unavailable (not measured)\nBackend: $backend\nCurrent app: ${service?.currentPackage ?: "Unavailable"}\nActivity: ${service?.currentActivity ?: "Unavailable"}\nSafety: $safety")
        Text("Controller state: Unavailable\nLast action result: Unavailable\nRetries: Unavailable\nSelected target: Unavailable\nGrounding: Unavailable\nClick coordinates: Unavailable")
        Button(onClick = { revision++ }) { Text("Refresh runtime ($revision)") }
        Button(enabled = enabled && !busy, onClick = {
            clear(); busy = true; status = "Capturing explicitly requested frame"
            val requestGeneration = generation
            captureJob = scope.launch {
                try {
                    val result = session.capture(true)
                    if (generation != requestGeneration || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) result.close()
                    else { frame = result; status = "Local masked preview + OCR only; not VLM grounding" }
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (generation == requestGeneration) status = "Capture unavailable: permission, changed screen, age or OCR check failed" }
                finally { if (generation == requestGeneration) busy = false }
            }
        }) { Text("Capture screen now") }
        Text("MediaProjection granted: ${ScreenshotCapture.hasPermission()}\n$status")
        frame?.let { captured ->
            Text("Snapshot hash: ${UiStateHasher.hash(captured.state.snapshot)}\nAccessibility nodes: ${captured.state.snapshot.nodes.size}\nOCR regions: ${captured.state.ocr.size}\nOCR confidence: Unavailable when zero\nCyan: Accessibility; yellow: OCR; magenta: model advice only")
            Canvas(Modifier.fillMaxWidth().aspectRatio(captured.bitmap.width.toFloat() / captured.bitmap.height)) {
                if (!captured.bitmap.isRecycled) {
                    drawImage(captured.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    val sx = size.width / captured.bitmap.width
                    val sy = size.height / captured.bitmap.height
                    fun box(r: com.unoone.agent.core.device.RectData, color: Color) {
                        drawRect(color, Offset(r.left * sx, r.top * sy), Size((r.right-r.left)*sx, (r.bottom-r.top)*sy), style = Stroke(2f))
                    }
                    captured.state.snapshot.nodes.forEach { box(it.bounds, Color.Cyan) }
                    captured.state.ocr.forEach { box(it.bounds, Color.Yellow) }
                    resultBox?.let { b ->
                        drawRect(Color.Magenta, Offset((b.left * size.width).toFloat(), (b.top * size.height).toFloat()),
                            Size(((b.right-b.left)*size.width).toFloat(), ((b.bottom-b.top)*size.height).toFloat()), style = Stroke(3f))
                    }
                }
            }
            TextButton(onClick = { clear() }) { Text("Discard screenshot") }
            Text("Captured image, NOT live. Local analysis may see private content that is unmarked or unknown. Known sensitive screens are denied. Image and answer are not logged or exported. Model advice never authorizes clicks, credentials or payments.")
            OutlinedTextField(value = question, onValueChange = { question = it.take(1024) }, label = { Text("Ask about the captured screen") }, enabled = !busy)
            Row {
                Checkbox(checked = approved, onCheckedChange = { approved = it }, enabled = !busy)
                Text("I reviewed this capture and approve local processing of this image only")
            }
            Button(enabled = enabled && approved && !busy && interpretImage != null, onClick = {
                val requestGeneration = generation
                busy = true; interpretation = null
                captureJob = scope.launch {
                    try {
                        ReviewedVisionSession.approve(captured, approved).use { envelope ->
                            approved = false
                            val answer = interpretImage!!.invoke(captured.state, envelope, question)
                            if (generation == requestGeneration) {
                                resultBox = runCatching { com.unoone.agent.localbrain.GroundingBox.parse(answer) }.getOrNull()
                                val age = android.os.SystemClock.elapsedRealtime() - captured.state.snapshot.capturedAtMs
                                interpretation = "Captured screen advice (${age / 1000}s old; ${if (age > 5000) "STALE for actions" else "not action authorization"}):\n$answer"
                                status = "Local model returned advice; no action executed"
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (generation == requestGeneration) status = "Analysis unavailable: expired capture, sensitive screen, model or image admission failed. No action executed."
                    } finally { if (generation == requestGeneration) { busy = false; approved = false } }
                }
            }) { Text("Analyze captured screen locally") }
            if (interpretImage == null) Text("Shared local vision runtime is not connected")
        } ?: Text("Snapshot hash / nodes / OCR: Unavailable (no retained capture)")
        Text(interpretation ?: "Model image interpretation: unavailable; local preview is not model input")
        Button(onClick = {
            // Allowlist only booleans and native enum; no arbitrary strings/errors or observed content.
            val report = "UnoOne developer diagnostics\nMaster enabled: ${AgentRuntimeGate.isEnabled()}\nProjection granted: ${ScreenshotCapture.hasPermission()}\nAccessibility available: ${UnoOneAccessibilityService.isEnabled()}\nSafety: ${safety.name}\nProfile hash, controller, action, retries: unavailable\nScreen content intentionally excluded."
            clear()
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
            }, "Share sanitized diagnostics"))
        }) { Text("Export sanitized diagnostics") }
    }
}
