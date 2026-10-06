package com.unoone.agent

import com.unoone.agent.core.latency.*
import com.unoone.agent.voice.VoiceLatency

import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.runtime.VoiceAdmissionTicket
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.ui.theme.UnoOneTheme
import com.unoone.agent.voice.VoiceActivityPolicy
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Expert Floating Service: Provides a 24/7 AI interface that stays on top of other apps.
 * Drag-and-drop floating bubble with an integrated chat/voice overlay.
 */
class FloatingAgentService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayOwner: com.unoone.agent.core.overlay.OwnOverlayBroker? = null
    private val ownedWindows = linkedMapOf<String, Pair<View, WindowManager.LayoutParams>>()
    private var stopRegistration: AutoCloseable? = null
    private var permissionRegistration: AutoCloseable? = null
    private var systemPermissionRegistration: AutoCloseable? = null
    private var bubbleView: View? = null
    private var chatOverlayView: View? = null

    companion object {
        private const val CHANNEL_ID = "floating_agent_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var voiceModule: VoiceModule
    private var cancelFloatingCapture: (() -> Unit)? = null
    private var floatingCaptureIdle: () -> Boolean = { true }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // 0C-1: Must start as foreground service to prevent being killed by the system
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        if (!AgentRuntimeGate.isEnabled()) {
            stopSelf()
            return
        }

        val app = application as UnoOneApplication
        orchestrator = app.orchestrator
        // Reuse the orchestrator's shared VoiceModule instead of creating a duplicate
        voiceModule = orchestrator.voiceModule

        // Detachable, independent listeners: never overwrite MainActivity's permission owner.
        val appContext = applicationContext
        val permissionListener: com.unoone.agent.core.util.PermissionListener = { _ ->
            appContext.startActivity(Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val systemListener: com.unoone.agent.core.util.SystemPermissionListener = { _ ->
            appContext.startActivity(Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val runtimeCallbacks = orchestrator.onPermissionRequiredMulticast
        val systemCallbacks = orchestrator.onSystemPermissionRequiredMulticast
        runtimeCallbacks.add(permissionListener)
        systemCallbacks.add(systemListener)
        permissionRegistration = AutoCloseable { runtimeCallbacks.remove(permissionListener) }
        systemPermissionRegistration = AutoCloseable { systemCallbacks.remove(systemListener) }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayOwner = com.unoone.agent.overlay.OwnOverlayBridge.install(object : com.unoone.agent.core.overlay.OwnOverlayBroker.WindowPort {
            override fun add(id: String) {
                val (view, params) = checkNotNull(ownedWindows[id])
                check(android.provider.Settings.canDrawOverlays(this@FloatingAgentService))
                val owner = checkNotNull(overlayOwner)
                com.unoone.agent.overlay.OwnOverlayBridge.registerView(owner, id, view)
                try { windowManager.addView(view, params) } catch (t: Throwable) {
                    if (!view.isAttachedToWindow)
                        com.unoone.agent.overlay.OwnOverlayBridge.unregisterView(owner, id, view)
                    throw t
                }
            }
            override fun remove(id: String) {
                val (view, _) = checkNotNull(ownedWindows[id])
                // Synchronous Main-thread removal ACK; caller still needs native window proof.
                windowManager.removeViewImmediate(view)
                com.unoone.agent.overlay.OwnOverlayBridge.unregisterView(checkNotNull(overlayOwner), id, view)
            }
        }, stopAvailable = { com.unoone.agent.overlay.StopSurfaceAdmission.nativeStopNotificationAvailable(applicationContext) },
            captureIdle = { floatingCaptureIdle() && voiceModule.isForegroundCaptureIdle() })
        stopRegistration = GlobalTaskCancellation.register(this) { service ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                service.cancelFloatingCapture?.invoke()
                com.unoone.agent.overlay.FloatingContextEvidence.current = null
                runCatching { service.overlayOwner?.let { com.unoone.agent.overlay.OwnOverlayBridge.stop(it, AgentRuntimeGate.isEnabled()) } }
                    .onFailure { Logger.e("Overlay Stop cleanup failed", it) }
            }
        }
        showFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRuntimeGate.isEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Only dispatch lifecycle events if not already at RESUMED (onStartCommand can be
        // called multiple times with START_STICKY, and RESUMED→STARTED is an invalid transition)
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        return START_STICKY
    }

    private fun showFloatingBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 500
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@FloatingAgentService)
            setViewTreeViewModelStoreOwner(this@FloatingAgentService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAgentService)

            setContent {
                UnoOneTheme {
                    FloatingBubbleUI(
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager.updateViewLayout(this, params)
                        },
                        onClick = { toggleChatOverlay() }
                    )
                }
            }
        }

        bubbleView = composeView
        ownedWindows["bubble"] = composeView to params
        overlayOwner!!.register("bubble", idle = true)
    }

    private fun toggleChatOverlay() {
        // A bubble tap is an explicit user interaction. Use it to recover the microphone FGS when
        // Android/HyperOS killed an earlier sticky instance; background Application startup is not
        // always allowed to launch a microphone foreground service on Android 14+.
        runCatching { VoiceService.start(this) }
            .onFailure { Logger.w("FloatingAgentService: voice service restart failed: ${it.message}") }
        showChatOverlay()
    }

    private var chatSourceSessionOpen = false

    private fun showChatOverlay() {
        // Only an explicit new chat session captures a source. A visible/resumed chat retains
        // its original scope, including its original timestamp; stale admission asks for an app.
        if (!chatSourceSessionOpen) {
            com.unoone.agent.overlay.FloatingContextEvidence.current =
                com.unoone.agent.overlay.FloatingContextEvidence.capture()
            chatSourceSessionOpen = true
        }
        if (chatOverlayView != null) { overlayOwner?.desired("chat", true); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.5f
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@FloatingAgentService)
            setViewTreeViewModelStoreOwner(this@FloatingAgentService)
            setViewTreeSavedStateRegistryOwner(this@FloatingAgentService)

            setContent {
                UnoOneTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Background click to close
                        Box(modifier = Modifier.fillMaxSize().clickable { hideChatOverlay() })

                        ChatOverlayCard(
                            modifier = Modifier.align(Alignment.Center),
                            serviceContext = this@FloatingAgentService,
                            orchestrator = orchestrator,
                            voiceModule = voiceModule,
                            onClose = { hideChatOverlay() },
                            registerCaptureClose = { cancelFloatingCapture = it },
                            registerCaptureIdle = { floatingCaptureIdle = it }
                        )
                    }
                }
            }
        }

        chatOverlayView = composeView
        ownedWindows["chat"] = composeView to params
        overlayOwner!!.register("chat")
    }

    private fun hideChatOverlay() {
        cancelFloatingCapture?.invoke()
        if (chatOverlayView != null) overlayOwner?.desired("chat", false)
        // desired(false) synchronously removes the window; reopening is explicit fresh scope.
        chatSourceSessionOpen = false
        com.unoone.agent.overlay.FloatingContextEvidence.current = null
    }

    override fun onDestroy() {
        cancelFloatingCapture?.invoke()
        stopRegistration?.close()
        permissionRegistration?.close(); permissionRegistration = null
        systemPermissionRegistration?.close(); systemPermissionRegistration = null
        runCatching { overlayOwner?.let { com.unoone.agent.overlay.OwnOverlayBridge.detach(it) } }
            .onFailure { Logger.e("Overlay destruction failed", it) }
        overlayOwner = null
        ownedWindows.values.forEach { (view, _) ->
            runCatching { (view as? ComposeView)?.disposeComposition() }
                .onFailure { Logger.e("Overlay disposal failed", it) }
        }
        ownedWindows.clear(); bubbleView = null; chatOverlayView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopForeground(STOP_FOREGROUND_REMOVE)
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the floating AI bubble running"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        // User-perceptible FGS notification (Play policy): the user toggled the bubble on, and can
        // pause/stop from the notification. No silent background surface.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .addAction(android.R.drawable.ic_media_pause, "Stop", TaskStopReceiver.pendingIntent(this))
            .setContentTitle("UnoOne active")
            .setContentText("Floating agent ready — Stop cancels current tasks")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

@Composable
fun FloatingBubbleUI(onDrag: (Float, Float) -> Unit, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(64.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clickable { onClick() },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 12.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                // Eyes-free (WS6): TalkBack reads this when the floating bubble gets focus. The
                // bubble is both tap (open chat) and drag (move), so the label says both.
                contentDescription = "UnoOne AI assistant — double tap to open, drag to move",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun ChatOverlayCard(
    modifier: Modifier = Modifier,
    serviceContext: android.content.Context,
    orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule,
    onClose: () -> Unit,
    registerCaptureClose: ((() -> Unit)?) -> Unit = {},
    registerCaptureIdle: (() -> Boolean) -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var ownerHandle by remember { mutableStateOf<VoiceModule.CaptureHandle?>(null) }
    var starting by remember { mutableStateOf<Any?>(null) }
    var captureEpoch by remember { mutableStateOf(0L) }
    var recordingGeneration by remember { mutableStateOf<Long?>(null) }
    val cueView = androidx.compose.ui.platform.LocalView.current
    var captureTrace by remember { mutableStateOf<LatencyToken?>(null) }
    var captureIngress by remember { mutableStateOf<com.unoone.agent.core.voice.VoiceIngress?>(null) }
    val review by orchestrator.unifiedVoice.review.collectAsState()
    val voiceStatus by orchestrator.unifiedVoice.status.collectAsState()
    var legacyReview by remember { mutableStateOf<Pair<String, (Boolean) -> Unit>?>(null) }
    DisposableEffect(orchestrator) {
        val listener: com.unoone.agent.core.util.ConfirmationListener = { message, respond -> legacyReview = message to respond }
        orchestrator.onConfirmationRequiredMulticast.add(listener)
        onDispose { orchestrator.onConfirmationRequiredMulticast.remove(listener); legacyReview = null }
    }
    val ownsCapture = remember { mutableStateOf(false) }
    val latestAmplitude = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val voiceRoutes = remember(scope) { CaptureRoutingLifetime(scope) }
    val steps by orchestrator.timelineSteps.collectAsState()

    val amplitudeListener = remember<(Float) -> Unit> {
        { amplitude -> latestAmplitude.floatValue = amplitude }
    }
    fun closeCapture() {
        voiceRoutes.stop()
        captureEpoch++
        starting = null
        captureJob?.cancel()
        ownerHandle?.let { voiceModule.discardCapture(it) }
        ownerHandle = null
        ownsCapture.value = false
        isListening = false
        orchestrator.unifiedVoice.clear()
        VoiceService.foregroundSessionActive = false
    }
    DisposableEffect(voiceModule) {
        registerCaptureClose { closeCapture() }
        registerCaptureIdle { starting == null && voiceModule.isForegroundCaptureIdle() }
        voiceModule.addAmplitudeListener(amplitudeListener)
        onDispose {
            voiceModule.removeAmplitudeListener(amplitudeListener)
            closeCapture()
            registerCaptureClose(null)
            registerCaptureIdle { true }
        }
    }

    fun finishVoiceCapture(completed: Boolean = true) {
        val owner = ownerHandle ?: return
        val epoch = captureEpoch
        val ingress = captureIngress ?: return
        val trace = captureTrace
        val generation = ingress.captureGlobalGeneration
        if (!ownsCapture.value) return
        ownsCapture.value = false
        isListening = false
        captureJob?.cancel()
        captureJob = null
        captureJob = scope.launch {
            var routed = false
            try {
                VoiceLatency.recorder.mark(trace, LatencyStage.ENDPOINT_DECISION)
                val result = voiceModule.stopAndTranscribe(owner, trace)
                voiceRoutes.retire(owner, ownerHandle) {
                    ownerHandle = null
                    starting = null
                    captureJob = null
                    VoiceService.foregroundSessionActive = false
                }
                if (epoch != captureEpoch) return@launch
                if (result is Result.Success && com.unoone.agent.voice.VoiceControlPolicy.isStop(result.data)) { routed = true; voiceRoutes.route { try { kotlinx.coroutines.withContext(CurrentLatencyContext(trace)) { orchestrator.unifiedVoice.accept(ingress.copy(transcript = result.data), trace) } } finally { VoiceLatency.recorder.closeCapture(trace) } }; return@launch }
                if (generation != GlobalTaskCancellation.generation || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled()) return@launch
                if (!com.unoone.agent.voice.UtteranceCompletionPolicy.eligible(com.unoone.agent.voice.UtteranceCompletionPolicy.endpointReason(completed), (result as? Result.Success)?.data)) {
                    if (!completed) voiceModule.speakAwait(com.unoone.agent.voice.UtteranceCompletionPolicy.SHORTER_CUE)
                    return@launch
                }
                when (result) {
                    is Result.Success -> {
                        val command = result.data.trim()
                        if (command.isBlank()) {
                            voiceModule.speakAwait("I didn't hear a command. Tap the microphone and try again.")
                        } else {
                            routed = true
                            voiceRoutes.route {
                            VoiceService.beginForegroundTask()
                            try {
                                kotlinx.coroutines.withContext(CurrentLatencyContext(trace)) { orchestrator.unifiedVoice.accept(ingress.copy(transcript = command), trace) }
                            } finally {
                                VoiceService.endForegroundTask()
                                VoiceLatency.recorder.closeCapture(trace)
                            }
                            }
                        }
                    }
                    is Result.Error -> {
                        Logger.w("FloatingAgentService: transcription failed: ${result.message}")
                        voiceModule.speakAwait("I couldn't understand that. Tap the microphone and try again.")
                    }
                }
            } finally {
                if (!routed) VoiceLatency.recorder.closeCapture(trace)
                voiceRoutes.retire(owner, ownerHandle) {
                    voiceModule.discardCapture(owner)
                    ownerHandle = null
                    VoiceService.foregroundSessionActive = false
                }
            }
        }
    }

    fun startVoiceCapture() {
        if (starting != null || ownerHandle != null || ownsCapture.value) return
        val startOwner = Any()
        starting = startOwner
        val generation = GlobalTaskCancellation.generation
        recordingGeneration = generation
        captureJob = scope.launch {
            try {
            // Pause the passive wake recorder before the audible cue and one-shot capture so the
            // two AudioRecord owners never contend or transcribe UnoOne's own voice.
            VoiceService.foregroundSessionActive = true
            runCatching { VoiceService.start(serviceContext) }
                .onFailure { Logger.w("FloatingAgentService: voice service unavailable: ${it.message}") }
            if (voiceModule.isSpeechBusy()) { VoiceService.foregroundSessionActive = false; return@launch }
            captureIngress = orchestrator.unifiedVoice.capture(floating = true)
            captureTrace = captureIngress?.let { VoiceLatency.recorder.forRequest(it.requestId) }
            VoiceLatency.recorder.mark(captureTrace, LatencyStage.MIC_REQUEST)
            val spokenCue = com.unoone.agent.voice.ListeningCuePolicy.selected(serviceContext) == com.unoone.agent.voice.ListeningCue.SPOKEN
            if (generation != GlobalTaskCancellation.generation || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled()) { VoiceService.foregroundSessionActive = false; return@launch }
            if (starting !== startOwner) return@launch
            latestAmplitude.floatValue = 0f
            val captureResult = voiceModule.startForegroundCapture(serviceContext, scope, captureTrace) {
                if (spokenCue) {
                    VoiceLatency.recorder.mark(captureTrace, LatencyStage.CUE_REQUEST)
                    check(voiceModule.speakAwait("Listening. Say one command.", trace = captureTrace) !is Result.Error)
                }
                check(starting === startOwner && generation == GlobalTaskCancellation.generation)
            }
            when (val result = captureResult) {
                is Result.Success -> {
                    ownerHandle = result.data
                    ownsCapture.value = true
                    isListening = true
                    if (!spokenCue) {
                        val haptic = cueView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        android.widget.Toast.makeText(serviceContext, if (haptic) "Listening — haptic delivered" else "Listening — visual cue", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    captureJob = scope.launch {
                        var completed = false
                        var speechStarted = false
                        var silenceSince = 0L
                        val startedAt = System.currentTimeMillis()
                        while (
                            isActive && ownsCapture.value &&
                            System.currentTimeMillis() - startedAt < VoiceActivityPolicy.MAX_UTTERANCE_MS
                        ) {
                            delay(100L)
                            if (VoiceActivityPolicy.isSpeech(latestAmplitude.floatValue)) {
                                speechStarted = true
                                silenceSince = 0L
                            } else if (speechStarted) {
                                if (silenceSince == 0L) silenceSince = System.currentTimeMillis()
                                if (System.currentTimeMillis() - silenceSince >= VoiceActivityPolicy.TRAILING_SILENCE_MS) {
                                    completed = true
                                    break
                                }
                            }
                        }
                        finishVoiceCapture(completed)
                    }
                }
                is Result.Error -> {
                    VoiceService.foregroundSessionActive = false
                    Logger.w("FloatingAgentService: record start failed: ${result.message}")
                    voiceModule.speakAwait("The microphone could not start. Open UnoOne and check voice settings.")
                }
            }
            } finally { if (starting === startOwner) starting = null }
        }
    }

    // Reactive mic permission check — re-evaluated on every recomposition
    val hasMicPermission = ContextCompat.checkSelfPermission(
        serviceContext, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    Card(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .height(500.dp)
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SmartToy, contentDescription = "UnoOne AI", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("UnoOne AI", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    Text(voiceStatus)
                    legacyReview?.let { displayed ->
                        Text(displayed.first)
                        Row {
                            Button(onClick = { legacyReview = null; displayed.second(true) }) { Text("Confirm") }
                            Button(onClick = { legacyReview = null; displayed.second(false) }) { Text("Cancel") }
                        }
                    }
                    review?.let { displayed ->
                        Text(VoicePurposeAdapter.description(displayed))
                        Row {
                            Button(onClick = { scope.launch { orchestrator.unifiedVoice.button(displayed, com.unoone.agent.core.voice.ReviewDecision.CONFIRM) } }) { Text("Confirm") }
                            Button(onClick = { scope.launch { orchestrator.unifiedVoice.button(displayed, com.unoone.agent.core.voice.ReviewDecision.CANCEL) } }) { Text("Cancel") }
                        }
                    }
                    steps.forEachIndexed { index, step ->
                        // Eyes-free (WS6): the most recent step is a TalkBack live region, so a blind
                        // user hears progress ("Listening", "Processing", "Done") without touching the
                        // list. Earlier steps are plain text for review.
                        val isLatest = index == steps.lastIndex
                        Text(
                            text = "${step.status}: ${step.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (step.status.name.contains("FAILED")) MaterialTheme.colorScheme.error else Color.Unspecified,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .then(if (isLatest) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    // Eyes-free (WS6): a real floating label (not just a placeholder) so TalkBack
                    // announces the field's purpose when focus lands on it.
                    label = { Text("Command") },
                    placeholder = { Text("What can I help with?") },
                    singleLine = true,
                    shape = CircleShape
                )
                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (!hasMicPermission) {
                            // Redirect to MainActivity for permission grant
                            Toast.makeText(
                                serviceContext,
                                "Microphone permission required. Opening UnoOne...",
                                Toast.LENGTH_SHORT
                            ).show()
                            val intent = Intent(serviceContext, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            serviceContext.startActivity(intent)
                            return@IconButton
                        }
                        if (isListening) {
                            finishVoiceCapture()
                        } else {
                            startVoiceCapture()
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isListening) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Speak a command"
                    )
                }

                Spacer(Modifier.width(4.dp))

                FloatingActionButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            val cmd = text
                            text = ""
                            scope.launch { orchestrator.processCommand(cmd) }
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command")
                }
            }
        }
    }
}
