package com.unoone.agent

import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.runtime.VoiceAdmissionTicket
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.edit
import com.unoone.agent.browser.SecureBrowserModelLease
import com.unoone.agent.model.ModelDownloadWorker
import androidx.work.WorkManager
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.BrainSelectionPolicy
import com.unoone.agent.storage.PreferencesManager
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.model.E4bRuntimeCoordinator
import com.unoone.agent.core.model.E4bRuntimeState
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeController
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.securebrowser.SecureWebViewController
import com.unoone.agent.voice.VoiceAgentRuntime
import com.unoone.agent.voice.VoiceAgentState
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceModule
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

    private val _commandFlow = MutableSharedFlow<VoiceAdmissionTicket>(extraBufferCapacity = 16)
    val commandFlow: SharedFlow<VoiceAdmissionTicket> = _commandFlow.asSharedFlow()

    private val _isAgentEnabled = MutableStateFlow(true)
    override val isAgentEnabled: StateFlow<Boolean> = _isAgentEnabled.asStateFlow()

    private val selectionMutex = Mutex()
    private val preferences by lazy { PreferencesManager(this) }
    val brainProviderPreferences by lazy { BrainProviderPreferences(this) }
    @Volatile private var selectedProfile: BrainModelSpec? = null

    /** One-time migration, shared by startup and UI; always invoked off the main thread. */
    suspend fun resolveSelectedBrain(): BrainModelSpec = withContext(Dispatchers.IO) {
        selectionMutex.withLock {
            selectedProfile ?: run {
                val saved = preferences.selectedBrainManifestId
                val legacyInstalled = saved == null &&
                    ModelManager(this@UnoOneApplication).getLlmModelPath(BrainModelRegistry.GEMMA_4_E4B) != null
                val profile = BrainSelectionPolicy.resolve(saved, legacyInstalled)
                preferences.setSelectedBrainManifestId(profile.manifestId)
                selectedProfile = profile
                profile
            }
        }
    }

    /** Explicit user action only; installation and load failures never choose another profile. */
    suspend fun selectBrainProfile(profile: BrainModelSpec): Result<Unit> = withContext(Dispatchers.IO) {
        selectionMutex.withLock {
            if (ExclusiveBrainLeaseState.isActive() || secureBrowserModelLease.isActive() ||
                orchestrator.isBlindAidActive.value) {
                return@withLock Result.Error("Close the active exclusive mode before changing the brain.")
            }
            modelLoadJob?.cancelAndJoin()
            try {
                orchestrator.cancelLlmInference("explicit brain selection")
                if (!orchestrator.unloadLlmModel() || orchestrator.isPhoneBrainResident()) {
                    return@withLock Result.Error("Previous model did not acknowledge unload; selection is unchanged.")
                }
                preferences.setSelectedBrainManifestId(profile.manifestId)
                if (profile.runtime == com.unoone.agent.core.model.BrainRuntime.MNN) {
                    brainProviderPreferences.qwenOptIn = true
                }
                selectedProfile = profile
                Result.Success(Unit)
            } catch (error: Exception) {
                Result.Error("Could not select ${profile.displayName}: ${error.message}", error)
            }
        }
    }

    /** Prevents startup/onResume/memory-recovery callers from queueing duplicate multi-GB loads. */
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
        secureBrowserModelLease = SecureBrowserModelLease(this, orchestrator) {
            checkNotNull(selectedProfile) { "Brain selection is still being initialized; try again shortly." }
        }

        // Blind Aid releases the resident selected engine before camera analysis reaches steady state.
        // The Application owns the Secure Browser lease + ExclusiveBrainLeaseState, so it supplies
        // the safe-to-unload guard and guarded reload callback used when Blind Aid deactivates.
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
        // Exact-path and integrity-related filesystem work must never block Application.onCreate.
        appScope.launch(Dispatchers.IO) {
            val brainSpec = resolveSelectedBrain()
            val llmPath = modelManager.resolveBrainLoadPath(brainSpec)
            if (llmPath != null) {
                if (persistedEnabled) scheduleLlmLoad(llmPath, brainSpec, "initial")
            } else {
                Logger.i("UnoOneApplication: selected ${brainSpec.displayName} artifact is not installed yet")
            }
        }

        appScope.launch {
            commandFlow.collect { ticket ->
                val command = ticket.text
                if (!ticket.isCurrent()) { VoiceService.endForegroundTask(); return@collect }
                if (AgentRuntimeGate.isEnabled() && command.isNotBlank()) {
                    Logger.i("UnoOneApplication: received local voice command")
                    try {
                        orchestrator.processCommand(command, com.unoone.agent.core.model.InputType.VOICE, ticket.generation)
                    } finally {
                        VoiceService.endForegroundTask()
                        if (AgentRuntimeGate.isEnabled() && ticket.isCurrent()) {
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
        VoiceService.voiceTicketCallback = { ticket -> postVoiceCommand(ticket.text, ticket.generation) }
        // When the KWS loop fires a wake word, speak the listening cue through the shared voice
        // module off the audio thread so capture and TTS retain single-owner microphone discipline.
        VoiceService.onWakeWord = {
            if (AgentRuntimeGate.isEnabled()) {
                runCatching {
                    sharedVoiceModule.speakAwait(VoiceLanguage.wakeCue(selectedVoiceLanguage()))
                }.onFailure { Logger.w("UnoOneApplication: wake cue speak failed: ${it.message}") }
            }
        }
        if (persistedEnabled) {
            appScope.launch(Dispatchers.IO) {
                modelManager.repairKwsFromVerifiedEnglishAsr()
                try {
                    VoiceService.start(this@UnoOneApplication)
                } catch (e: Exception) {
                    Logger.e("Failed to auto-start VoiceService", e)
                }
            }
        }
    }

    fun postVoiceCommand(command: String) = postVoiceCommand(command, GlobalTaskCancellation.generation)

    fun postVoiceCommand(command: String, generation: Long) {
        if (!AgentRuntimeGate.isEnabled()) return
        // Safety controls bypass the serial command collector and its processing lock.
        if (com.unoone.agent.voice.VoiceControlPolicy.routeStop(command) {
                orchestrator.cancelCurrentCommand(speak = false)
                sharedVoiceModule.stopSpeaking()
            }) return
        // The command collector is intentionally serial. A spoken "Uno confirm" must therefore
        // resolve a safety prompt directly instead of waiting behind the command that is awaiting
        // that very confirmation.
        if (generation != GlobalTaskCancellation.generation) return
        if (orchestrator.resolvePendingVoiceConfirmation(command)) return
        VoiceService.beginForegroundTask()
        if (!_commandFlow.tryEmit(VoiceAdmissionTicket(command, generation))) {
            VoiceService.endForegroundTask()
            VoiceAgentRuntime.transition(VoiceAgentState.WAKE_LISTENING, "voice command queue full")
        }
    }

    /**
     * Persistent emergency stop. The gate closes synchronously before teardown begins, so racing
     * callbacks cannot start a new action while resources are being released.
     */
    override fun disableAgent() {
        if (!AgentRuntimeGate.isEnabled()) return
        AgentRuntimeGate.setEnabled(false)
        E4bRuntimeCoordinator.transition(E4bRuntimeState.DISABLED, detail = "explicit master disable")
        WorkManager.getInstance(this).cancelUniqueWork(ModelDownloadWorker.UNIQUE_WORK)
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
        E4bRuntimeCoordinator.transition(E4bRuntimeState.UNLOADED, detail = "explicit re-enable")
        _isAgentEnabled.value = true
        VoiceService.voiceCommandCallback = { command -> postVoiceCommand(command) }
        VoiceService.voiceTicketCallback = { ticket -> postVoiceCommand(ticket.text, ticket.generation) }
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
            manager.repairKwsFromVerifiedEnglishAsr()
            runCatching { VoiceService.start(this@UnoOneApplication) }
                .onFailure { Logger.e("AgentRuntime: failed to restart voice service", it) }

            // The model may have been installed while UnoOne was disabled; resolve it again instead
            // of relying only on the path remembered at process startup.
            val spec = resolveSelectedBrain()
            val path = manager.resolveBrainLoadPath(spec)
            if (path != null) {
                scheduleLlmLoad(path, spec, "enable")
            }
        }
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
        if (orchestrator.isLlmLoaded()) return
        appScope.launch(Dispatchers.IO) {
            val spec = resolveSelectedBrain()
            val path = ModelManager(this@UnoOneApplication).resolveBrainLoadPath(spec) ?: return@launch
            scheduleLlmLoad(path, spec, "recovery")
        }
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
                if (!AgentRuntimeGate.isEnabled() || selectedProfile?.manifestId != spec.manifestId) return@launch
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
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
                    if (!AgentRuntimeGate.isEnabled() || orchestrator.isBlindAidActive.value ||
                        selectedProfile?.manifestId != spec.manifestId) {
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
