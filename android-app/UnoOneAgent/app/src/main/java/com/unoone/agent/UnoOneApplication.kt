package com.unoone.agent

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.unoone.agent.core.util.Logger
import dagger.hilt.android.HiltAndroidApp
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@HiltAndroidApp
class UnoOneApplication : Application() {

    // Expert: Master Orchestrator accessible from anywhere (Activity or Service)
    lateinit var orchestrator: AgentOrchestrator
        private set

    // Single shared VoiceModule — used by orchestrator, ViewModel, and FloatingAgentService
    lateinit var sharedVoiceModule: VoiceModule
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * SharedFlow for voice commands from VoiceService.
     * Replaces the insecure BroadcastReceiver approach — commands are no longer
     * broadcast via Intent (which is visible in system logs even with setPackage).
     * VoiceService posts commands here, and the orchestrator collects them.
     */
    private val _commandFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commandFlow: SharedFlow<String> = _commandFlow.asSharedFlow()

    // Remembered so the brain can be reloaded after a memory-pressure unload (see onTrimMemory).
    @Volatile private var lastLlmPath: String? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("UnoOne starting up - Expert Mode")
        appContext = applicationContext

        val db = DatabaseProvider.getDatabase(this)

        // Create the shared VoiceModule first, then inject it into the orchestrator
        sharedVoiceModule = VoiceModule(this)

        orchestrator = AgentOrchestrator(
            this,
            db.noteDao(),
            db.actionLogDao(),
            db.memoryDao(),
            db.skillDao()
        )
        orchestrator.setVoiceModule(sharedVoiceModule)

        // Initialize audit logger with the action log DAO
        AuditLogger.initialize(db.actionLogDao())

        // Auto-load Gemma 4 .litertlm brain if a model file is present
        val modelManager = ModelManager(this, db.modelMetadataDao())
        modelManager.ensureModelDirectories()
        val llmPath = modelManager.getLlmModelPath()
        if (llmPath != null) {
            lastLlmPath = llmPath
            appScope.launch {
                val result = orchestrator.loadLlmModel(llmPath)
                if (result is com.unoone.agent.core.model.Result.Success) {
                    Logger.i("UnoOneApplication: Gemma brain loaded from $llmPath")
                } else {
                    Logger.w("UnoOneApplication: Gemma brain failed to load: ${(result as? com.unoone.agent.core.model.Result.Error)?.message}")
                }
            }
        }

        // Collect voice commands from SharedFlow and dispatch to orchestrator
        appScope.launch {
            commandFlow.collect { command ->
                if (command.isNotBlank()) {
                    Logger.i("UnoOneApplication: Received voice command: '$command'")
                    orchestrator.processCommand(command, com.unoone.agent.core.model.InputType.VOICE)
                }
            }
        }

        // Expert: Start background services for hands-free and floating assistant
        // Wire VoiceService static callback to route commands through SharedFlow
        VoiceService.voiceCommandCallback = { command -> postVoiceCommand(command) }

        try {
            VoiceService.start(this)
        } catch (e: Exception) {
            Logger.e("Failed to auto-start VoiceService", e)
        }
    }

    /**
     * Post a voice command to the SharedFlow. Called by VoiceService instead of
     * sending a broadcast Intent. This avoids exposing transcribed speech in system logs.
     */
    fun postVoiceCommand(command: String) {
        _commandFlow.tryEmit(command)
    }

    /**
     * Crash-safe memory pressure handling. Under *running* memory pressure (the app is in the
     * foreground and the OS is low on RAM) we unload the multi-GB Gemma brain — by far the largest
     * native allocation — rather than risk an OOM crash mid-inference. The brain is reloaded by
     * [reloadLlmIfUnloaded] when the activity returns to the foreground.
     *
     * We intentionally do NOT call [VoiceModule.release] here: the Sherpa engines are the live voice
     * path, release() is not reversible without re-initializing with the model directory (which is
     * not wired at startup), and tearing down voice mid-session would break ongoing recognition.
     * Unloading the LLM is the high-leverage, low-risk move; it is idempotent.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val isRunningPressure =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (isRunningPressure && orchestrator.isLlmLoaded()) {
            Logger.i("UnoOneApplication: memory pressure (level $level); unloading Gemma brain")
            appScope.launch {
                runCatching { orchestrator.unloadLlmModel() }
                    .onFailure { Logger.e("UnoOneApplication: LLM unload failed", it) }
            }
        }
    }

    /**
     * Reload the Gemma brain if a model was previously loaded but got unloaded by [onTrimMemory].
     * Called from [com.unoone.agent.MainActivity.onResume] so the agent recovers transparently when
     * the user returns to the app. No-op if the brain is already loaded or was never loaded.
     */
    fun reloadLlmIfUnloaded() {
        val path = lastLlmPath ?: return
        if (orchestrator.isLlmLoaded()) return
        Logger.i("UnoOneApplication: reloading Gemma brain after memory pressure")
        appScope.launch {
            val result = orchestrator.loadLlmModel(path)
            if (result is com.unoone.agent.core.model.Result.Success) {
                Logger.i("UnoOneApplication: Gemma brain reloaded from $path")
            } else {
                Logger.w("UnoOneApplication: Gemma brain reload failed: ${(result as? com.unoone.agent.core.model.Result.Error)?.message}")
            }
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}