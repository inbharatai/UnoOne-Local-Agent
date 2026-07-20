package com.unoone.agent

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Process
import android.content.Intent
import androidx.core.content.edit
import com.unoone.agent.browser.SecureBrowserModelLease
import com.unoone.agent.securebrowser.SecureWebViewController
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeController
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceAgentRuntime
import com.unoone.agent.voice.VoiceAgentState
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltAndroidApp
class UnoOneApplication : Application(), AgentRuntimeController {

    lateinit var orchestrator: AgentOrchestrator
        private set

    lateinit var sharedVoiceModule: VoiceModule
        private set

    /** Single application-owned lease used by every Secure Browser screen/session. */
    lateinit var secureBrowserModelLease: SecureBrowserModelLease
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _commandFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commandFlow: SharedFlow<String> = _commandFlow.asSharedFlow()

    private val _isAgentEnabled = MutableStateFlow(true)
    override val isAgentEnabled: StateFlow<Boolean> = _isAgentEnabled.asStateFlow()

    /** Remembered for restoring the main Gemma 4 brain after memory-pressure unload. */
    @Volatile private var lastLlmPath: String? = null

    /** Prevents startup/onResume/memory-recovery callers from queueing duplicate 2.5 GB loads. */
    private val modelLoadGate = ModelLoadGate()
    @Volatile private var modelLoadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val persistedEnabled = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGENT_ENABLED, true)
        AgentRuntimeGate.setEnabled(persistedEnabled)
        _isAgentEnabled.value = persistedEnabled
        Logger.i("UnoOne V2 starting — agent ${if (persistedEnabled) "enabled" else "disabled"}")

        val db = DatabaseProvider.getDatabase(this)
        val settingsPrefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        if (!settingsPrefs.getBoolean(KEY_PRIVATE_LOG_MIGRATION, false)) {
            appScope.launch(Dispatchers.IO) {
                runCatching { db.actionLogDao().redactLegacyPrivateContent() }
                    .onSuccess {
                        settingsPrefs.edit { putBoolean(KEY_PRIVATE_LOG_MIGRATION, true) }
                        Logger.i("UnoOneApplication: legacy private log fields removed")
                    }
                    .onFailure { Logger.e("UnoOneApplication: private log migration failed", it) }
            }
        }

        sharedVoiceModule = VoiceModule(this)
        VoiceService.sharedVoiceModuleProvider = { sharedVoiceModule }
        // Sherpa model construction performs file I/O and native initialization. Keep it off the
        // main thread so a cold offline launch cannot freeze Compose while voice models warm up.
        if (persistedEnabled) {
            appScope.launch(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val modelBaseDir = (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/models"
                sharedVoiceModule.reinitForLanguage(modelBaseDir)
            }
        }

        orchestrator = AgentOrchestrator(
            this,
            db.noteDao(),
            db.actionLogDao(),
            db.memoryDao(),
            db.skillDao()
        )
        orchestrator.setVoiceModule(sharedVoiceModule)
        if (persistedEnabled) {
            appScope.launch(Dispatchers.IO) {
                orchestrator.skillsModule.ensureBuiltIns()
            }
        }
        secureBrowserModelLease = SecureBrowserModelLease(this, orchestrator)

        // C1: Blind Aid unloads the 2.5 GB Gemma brain to free ~800 MB RAM for the camera (the
        // reported "system shuts down" lowmemorykiller OOM). The Application owns the Secure Browser
        // lease + ExclusiveBrainLeaseState, so it supplies the "safe to unload" guard and the reload
        // callback that honours those leases when Blind Aid is deactivated.
        orchestrator.brainReleaseGuard = {
            !ExclusiveBrainLeaseState.isActive() && !secureBrowserModelLease.isActive()
        }
        orchestrator.brainReloadCallback = { reloadLlmIfUnloaded() }
        orchestrator.brainLoadCancelCallback = {
            modelLoadJob?.cancel()
            Logger.i("UnoOneApplication: cancelled pending brain load for Blind Aid")
        }

        AuditLogger.initialize(db.actionLogDao())

        val modelManager = ModelManager(this, db.modelMetadataDao())
        modelManager.ensureModelDirectories()
        val brainSpec = BrainModelRegistry.GEMMA_4_E2B
        val llmPath = modelManager.getLlmModelPath(brainSpec)
        if (llmPath != null) {
            lastLlmPath = llmPath
            if (persistedEnabled) scheduleLlmLoad(llmPath, brainSpec, "initial")
        }

        appScope.launch {
            commandFlow.collect { command ->
                if (AgentRuntimeGate.isEnabled() && command.isNotBlank()) {
                    Logger.i("UnoOneApplication: received local voice command")
                    try {
                        orchestrator.processCommand(command, com.unoone.agent.core.model.InputType.VOICE)
                    } finally {
                        VoiceService.endForegroundTask()
                        if (AgentRuntimeGate.isEnabled()) {
                            VoiceAgentRuntime.transition(
                                VoiceAgentState.WAKE_LISTENING,
                                "voice command completed"
                            )
                        }
                    }
                }
            }
        }

        VoiceService.voiceCommandCallback = { command -> postVoiceCommand(command) }
        // Eyes-free (WS2): when the KWS loop fires a wake word, speak the "I'm listening" cue via the
        // shared VoiceModule. Dispatched off the audio thread (Dispatchers.IO) so the cue does not
        // block the spotting loop from capturing the command that follows.
        VoiceService.onWakeWord = {
            if (AgentRuntimeGate.isEnabled()) {
                runCatching {
                    sharedVoiceModule.speakAwait(VoiceLanguage.wakeCue(selectedVoiceLanguage()))
                }.onFailure { Logger.w("UnoOneApplication: wake cue speak failed: ${it.message}") }
            }
        }
        if (persistedEnabled) {
            appScope.launch(Dispatchers.IO) {
                modelManager.repairVadFromVerifiedEnglishAsr()
                try {
                    VoiceService.start(this@UnoOneApplication)
                } catch (e: Exception) {
                    Logger.e("Failed to auto-start VoiceService", e)
                }
            }
        }
    }

    fun postVoiceCommand(command: String) {
        if (!AgentRuntimeGate.isEnabled()) return
        VoiceService.beginForegroundTask()
        if (!_commandFlow.tryEmit(command)) VoiceService.endForegroundTask()
    }

    /**
     * Persistent emergency stop. The gate closes synchronously before teardown begins, so racing
     * callbacks cannot start a new action while resources are being released.
     */
    override fun disableAgent() {
        if (!AgentRuntimeGate.isEnabled()) return
        AgentRuntimeGate.setEnabled(false)
        getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_AGENT_ENABLED, false)
        }
        _isAgentEnabled.value = false

        modelLoadJob?.cancel()
        modelLoadJob = null
        VoiceService.clearAudioOwnership()
        VoiceService.voiceCommandCallback = { }
        VoiceService.onWakeWord = { }
        VoiceService.sharedVoiceModuleProvider = null
        VoiceService.stop(this)
        sharedVoiceModule.stopRecording()
        sharedVoiceModule.stopSpeaking()
        orchestrator.shutdownForDisable()
        val floatingServiceIntent = Intent(this, FloatingAgentService::class.java)
        val projectionServiceIntent =
            Intent(this, com.unoone.agent.screenshot.MediaProjectionService::class.java)
        stopService(floatingServiceIntent)
        stopService(projectionServiceIntent)
        SecureWebViewController.stopAllForDisable()

        appScope.launch(Dispatchers.IO) {
            runCatching { secureBrowserModelLease.release(restore = false) }
            runCatching { orchestrator.unloadLlmModel() }
            listOf("voice", "speech", "browser", "document", "uploads").forEach { name ->
                runCatching { java.io.File(cacheDir, name).deleteRecursively() }
            }
        }
        Logger.i("AgentRuntime: disabled; microphone, inference, speech and automation stopped")
        VoiceAgentRuntime.clearForDisable()
    }

    /** Explicit user-only re-enable. No old prompt, recording, or browser task is resumed. */
    override fun enableAgent() {
        if (AgentRuntimeGate.isEnabled()) return
        getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean(KEY_AGENT_ENABLED, true)
        }
        AgentRuntimeGate.setEnabled(true)
        _isAgentEnabled.value = true
        VoiceService.voiceCommandCallback = { command -> postVoiceCommand(command) }
        VoiceService.sharedVoiceModuleProvider = { sharedVoiceModule }
        VoiceAgentRuntime.transition(VoiceAgentState.INITIALISING, "explicit enable")
        VoiceService.onWakeWord = {
            if (AgentRuntimeGate.isEnabled()) {
                runCatching {
                    sharedVoiceModule.speakAwait(VoiceLanguage.wakeCue(selectedVoiceLanguage()))
                }
            }
        }
        appScope.launch(Dispatchers.IO) {
            val modelBaseDir = (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/models"
            sharedVoiceModule.reinitForLanguage(modelBaseDir)
            orchestrator.skillsModule.ensureBuiltIns()
            val manager = ModelManager(this@UnoOneApplication)
            manager.ensureModelDirectories()
            manager.repairVadFromVerifiedEnglishAsr()
            runCatching { VoiceService.start(this@UnoOneApplication) }
                .onFailure { Logger.e("AgentRuntime: failed to restart voice service", it) }
        }
        reloadLlmIfUnloaded()
        Logger.i("AgentRuntime: enabled by explicit user action")
    }

    private fun selectedVoiceLanguage(): String =
        VoiceLanguage.normalize(
            getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)
                .getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
        )

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val runningPressure =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (!runningPressure) return

        when {
            secureBrowserModelLease.isActive() -> {
                Logger.i("UnoOneApplication: memory pressure; releasing Secure Browser Gemma lease")
                appScope.launch {
                    val result = secureBrowserModelLease.release(restore = false)
                    if (result is Result.Error) {
                        Logger.e("UnoOneApplication: browser lease release failed: ${result.message}")
                    }
                }
            }
            orchestrator.isLlmLoaded() && !ExclusiveBrainLeaseState.isActive() -> {
                Logger.i("UnoOneApplication: memory pressure; unloading main Gemma brain")
                appScope.launch {
                    runCatching { orchestrator.unloadLlmModel() }
                        .onFailure { Logger.e("UnoOneApplication: LLM unload failed", it) }
                }
            }
        }
    }

    /** Reloads the main brain only when no exclusive mode currently owns Gemma. */
    fun reloadLlmIfUnloaded() {
        if (!AgentRuntimeGate.isEnabled()) return
        if (ExclusiveBrainLeaseState.isActive() || secureBrowserModelLease.isActive()) return
        val path = lastLlmPath ?: return
        if (orchestrator.isLlmLoaded()) return
        val spec = BrainModelRegistry.GEMMA_4_E2B
        scheduleLlmLoad(path, spec, "recovery")
    }

    /**
     * Starts one native model load at a time. The gate is acquired synchronously before launching
     * the coroutine so an immediate Activity.onResume cannot observe a false-ready state and enqueue
     * another load behind it. LiteRT initialization remains on IO and never occupies the UI thread.
     */
    private fun scheduleLlmLoad(
        path: String,
        spec: com.unoone.agent.core.model.BrainModelSpec,
        reason: String
    ) {
        if (!AgentRuntimeGate.isEnabled()) return
        if (!modelLoadGate.tryAcquire()) {
            Logger.i("UnoOneApplication: skipped duplicate $reason ${spec.displayName} load; one is already in flight")
            return
        }
        Logger.i("UnoOneApplication: starting $reason ${spec.displayName} load")
        modelLoadJob = appScope.launch(Dispatchers.IO) {
            try {
                // Let the landing screen and direct camera/voice controls become interactive first.
                // Blind Aid cancels this delay, avoiding contention with a multi-GB native load.
                if (reason == "initial") delay(8_000L)
                if (!AgentRuntimeGate.isEnabled()) return@launch
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                // A browser/Blind-Aid transition may have completed between scheduling and execution.
                if (ExclusiveBrainLeaseState.isActive() ||
                    secureBrowserModelLease.isActive() ||
                    orchestrator.isBlindAidActive.value
                ) {
                    Logger.i("UnoOneApplication: cancelled $reason brain load; exclusive mode is active")
                    return@launch
                }
                if (orchestrator.isLlmLoaded()) {
                    Logger.i("UnoOneApplication: skipped $reason brain load; model became ready")
                    return@launch
                }
                val result = orchestrator.loadLlmModel(path, spec)
                if (result is Result.Success) {
                    if (!AgentRuntimeGate.isEnabled() || orchestrator.isBlindAidActive.value) {
                        // Native model creation is not cancellable once entered. If Blind Aid was
                        // activated mid-load, release the newly-created brain immediately.
                        orchestrator.unloadLlmModel()
                        Logger.i("UnoOneApplication: released brain that finished loading after its lease was cancelled")
                    } else {
                        Logger.i("UnoOneApplication: ${spec.displayName} loaded from $path ($reason)")
                    }
                } else {
                    Logger.w(
                        "UnoOneApplication: ${spec.displayName} $reason load failed: " +
                            (result as? Result.Error)?.message
                    )
                }
            } finally {
                modelLoadGate.release()
                modelLoadJob = null
            }
        }
    }

    companion object {
        const val SETTINGS_PREFS = "unoone_settings"
        const val KEY_AGENT_ENABLED = "agent_enabled"
        private const val KEY_PRIVATE_LOG_MIGRATION = "private_log_migration_v2"
        lateinit var appContext: Context
            private set
    }
}
