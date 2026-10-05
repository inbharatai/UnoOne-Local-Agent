package com.unoone.agent

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

        // Expert: handle permissions by redirecting to MainActivity
        orchestrator.onPermissionRequired = { _ ->
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Permissions required. Opening UnoOne...", Toast.LENGTH_SHORT).show()
        }

        // System permissions (Accessibility / MediaProjection / Overlay) need a settings/consent
        // screen — hand off to MainActivity, which deep-links to the right one and resumes the
        // stashed command on return. Mirrors the runtime-perm redirect above.
        orchestrator.onSystemPermissionRequired = { _ ->
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "System access required. Opening UnoOne...", Toast.LENGTH_SHORT).show()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
        windowManager.addView(composeView, params)
    }

    private fun toggleChatOverlay() {
        // A bubble tap is an explicit user interaction. Use it to recover the microphone FGS when
        // Android/HyperOS killed an earlier sticky instance; background Application startup is not
        // always allowed to launch a microphone foreground service on Android 14+.
        runCatching { VoiceService.start(this) }
            .onFailure { Logger.w("FloatingAgentService: voice service restart failed: ${it.message}") }
        if (chatOverlayView == null) showChatOverlay() else hideChatOverlay()
    }

    private fun showChatOverlay() {
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
                            onClose = { hideChatOverlay() }
                        )
                    }
                }
            }
        }

        chatOverlayView = composeView
        windowManager.addView(composeView, params)
    }

    private fun hideChatOverlay() {
        chatOverlayView?.let {
            windowManager.removeView(it)
            chatOverlayView = null
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        // 0C-10: Dispose ComposeView compositions before removing from window
        // to prevent memory leaks from lingering compositions
        (bubbleView as? ComposeView)?.disposeComposition()
        (chatOverlayView as? ComposeView)?.disposeComposition()

        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        hideChatOverlay()

        // 0C-1: Stop foreground service properly
        stopForeground(STOP_FOREGROUND_REMOVE)

        // No voiceModule.release() here — it's shared with the orchestrator and will be
        // released when the Application is destroyed or the ViewModel is cleared.
        store.clear()
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
            .setContentTitle("UnoOne active")
            .setContentText("Floating agent is active — tap to pause / stop")
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
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var recordingGeneration by remember { mutableStateOf<Long?>(null) }
    val ownsCapture = remember { mutableStateOf(false) }
    val latestAmplitude = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val steps by orchestrator.timelineSteps.collectAsState()

    val amplitudeListener = remember<(Float) -> Unit> {
        { amplitude -> latestAmplitude.floatValue = amplitude }
    }
    DisposableEffect(voiceModule) {
        voiceModule.addAmplitudeListener(amplitudeListener)
        onDispose {
            voiceModule.removeAmplitudeListener(amplitudeListener)
            captureJob?.cancel()
            if (ownsCapture.value) {
                ownsCapture.value = false
                runCatching { voiceModule.stopRecording() }
                VoiceService.foregroundSessionActive = false
            }
        }
    }

    fun finishVoiceCapture() {
        val generation = recordingGeneration ?: return
        if (!ownsCapture.value) return
        ownsCapture.value = false
        isListening = false
        captureJob?.cancel()
        captureJob = null
        scope.launch {
            try {
                val result = voiceModule.stopAndTranscribe()
                if (result is Result.Success && com.unoone.agent.voice.VoiceControlPolicy.isStop(result.data)) { orchestrator.processCommand(result.data, InputType.VOICE, generation); return@launch }
                if (generation != GlobalTaskCancellation.generation || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled()) return@launch
                when (result) {
                    is Result.Success -> {
                        val command = result.data.trim()
                        if (command.isBlank()) {
                            voiceModule.speakAwait("I didn't hear a command. Tap the microphone and try again.")
                        } else {
                            VoiceService.beginForegroundTask()
                            try {
                                orchestrator.processCommand(command, InputType.VOICE, generation)
                            } finally {
                                VoiceService.endForegroundTask()
                            }
                        }
                    }
                    is Result.Error -> {
                        Logger.w("FloatingAgentService: transcription failed: ${result.message}")
                        voiceModule.speakAwait("I couldn't understand that. Tap the microphone and try again.")
                    }
                }
            } finally {
                VoiceService.foregroundSessionActive = false
            }
        }
    }

    fun startVoiceCapture() {
        if (ownsCapture.value) return
        val generation = GlobalTaskCancellation.generation
        recordingGeneration = generation
        scope.launch {
            // Pause the passive wake recorder before the audible cue and one-shot capture so the
            // two AudioRecord owners never contend or transcribe UnoOne's own voice.
            VoiceService.foregroundSessionActive = true
            runCatching { VoiceService.start(serviceContext) }
                .onFailure { Logger.w("FloatingAgentService: voice service unavailable: ${it.message}") }
            runCatching { voiceModule.stopSpeaking() }
            voiceModule.speakAwait("Listening. Say one command.")
            if (generation != GlobalTaskCancellation.generation || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled()) { VoiceService.foregroundSessionActive = false; return@launch }
            latestAmplitude.floatValue = 0f
            when (val result = voiceModule.startRecording(serviceContext, scope)) {
                is Result.Success -> {
                    ownsCapture.value = true
                    isListening = true
                    captureJob = scope.launch {
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
                                    break
                                }
                            }
                        }
                        finishVoiceCapture()
                    }
                }
                is Result.Error -> {
                    VoiceService.foregroundSessionActive = false
                    Logger.w("FloatingAgentService: record start failed: ${result.message}")
                    voiceModule.speakAwait("The microphone could not start. Open UnoOne and check voice settings.")
                }
            }
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
