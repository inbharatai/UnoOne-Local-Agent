package com.unoone.agent

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.ui.theme.UnoOneTheme
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.launch

/**
 * Expert Floating Service: Provides a 24/7 AI interface that stays on top of other apps.
 * World-class UI with drag-and-drop bubble and integrated chat/voice overlay.
 */
class FloatingAgentService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var chatOverlayView: View? = null
    
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
        
        val app = application as UnoOneApplication
        orchestrator = app.orchestrator
        voiceModule = VoiceModule(this)

        // Expert: handle permissions by redirecting to MainActivity
        orchestrator.onPermissionRequired = { missing ->
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(this, "Permissions required. Opening UnoOne...", Toast.LENGTH_SHORT).show()
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
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
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        bubbleView?.let { windowManager.removeView(it) }
        hideChatOverlay()
        store.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null
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
            Icon(Icons.Default.SmartToy, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun ChatOverlayCard(
    modifier: Modifier = Modifier,
    orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val steps by orchestrator.timelineSteps.collectAsState()

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
                Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("UnoOne AI", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    steps.forEach { step ->
                        Text(
                            text = "${step.status}: ${step.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (step.status.name.contains("FAILED")) MaterialTheme.colorScheme.error else Color.Unspecified,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("What can I help with?") },
                    shape = CircleShape
                )
                Spacer(Modifier.width(8.dp))
                
                IconButton(
                    onClick = {
                        if (isListening) {
                            isListening = false
                            scope.launch {
                                // Fix type mismatch by reading raw bytes from recorder
                                val pcmData = voiceModule.stopRecording()
                                if (pcmData.isNotEmpty()) {
                                    val result = voiceModule.transcribeWithAndroid()
                                    if (result is com.unoone.agent.core.model.Result.Success) {
                                        orchestrator.processCommand(result.data, com.unoone.agent.core.model.InputType.VOICE)
                                    }
                                }
                            }
                        } else {
                            isListening = true
                            voiceModule.startRecording(UnoOneApplication.appContext)
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isListening) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Mic, null)
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
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                }
            }
        }
    }
}
