package com.unoone.agent

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import com.unoone.agent.browser.SecureBrowserModelLease
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@HiltAndroidApp
class UnoOneApplication : Application() {

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

    /** Remembered for restoring the main Gemma 4 brain after memory-pressure unload. */
    @Volatile private var lastLlmPath: String? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("UnoOne V2 starting — Gemma 4 E2B local mode")
        appContext = applicationContext

        val db = DatabaseProvider.getDatabase(this)

        sharedVoiceModule = VoiceModule(this)
        appScope.launch {
            val modelBaseDir = (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/models"
            sharedVoiceModule.reinitForLanguage(modelBaseDir)
        }

        orchestrator = AgentOrchestrator(
            this,
            db.noteDao(),
            db.actionLogDao(),
            db.memoryDao(),
            db.skillDao()
        )
        orchestrator.setVoiceModule(sharedVoiceModule)
        secureBrowserModelLease = SecureBrowserModelLease(this, orchestrator)

        // C1: Blind Aid unloads the 2.5 GB Gemma brain to free ~800 MB RAM for the camera (the
        // reported "system shuts down" lowmemorykiller OOM). The Application owns the Secure Browser
        // lease + ExclusiveBrainLeaseState, so it supplies the "safe to unload" guard and the reload
        // callback that honours those leases when Blind Aid is deactivated.
        orchestrator.brainReleaseGuard = {
            !ExclusiveBrainLeaseState.isActive() && !secureBrowserModelLease.isActive()
        }
        orchestrator.brainReloadCallback = { reloadLlmIfUnloaded() }

        AuditLogger.initialize(db.actionLogDao())

        val modelManager = ModelManager(this, db.modelMetadataDao())
        modelManager.ensureModelDirectories()
        val brainSpec = BrainModelRegistry.GEMMA_4_E2B
        val llmPath = modelManager.getLlmModelPath(brainSpec)
        if (llmPath != null) {
            lastLlmPath = llmPath
            appScope.launch {
                val result = orchestrator.loadLlmModel(llmPath, brainSpec)
                if (result is Result.Success) {
                    Logger.i("UnoOneApplication: ${brainSpec.displayName} loaded from $llmPath")
                } else {
                    Logger.w(
                        "UnoOneApplication: ${brainSpec.displayName} failed to load: " +
                            (result as? Result.Error)?.message
                    )
                }
            }
        }

        appScope.launch {
            commandFlow.collect { command ->
                if (command.isNotBlank()) {
                    Logger.i("UnoOneApplication: received local voice command")
                    orchestrator.processCommand(command, com.unoone.agent.core.model.InputType.VOICE)
                }
            }
        }

        VoiceService.voiceCommandCallback = { command -> postVoiceCommand(command) }
        // Eyes-free (WS2): when the KWS loop fires a wake word, speak the "I'm listening" cue via the
        // shared VoiceModule. Dispatched off the audio thread (Dispatchers.IO) so the cue does not
        // block the spotting loop from capturing the command that follows.
        VoiceService.onWakeWord = {
            appScope.launch(Dispatchers.IO) {
                runCatching { sharedVoiceModule.speak("Yes, I'm listening.") }
                    .onFailure { Logger.w("UnoOneApplication: wake cue speak failed: ${it.message}") }
            }
        }
        try {
            VoiceService.start(this)
        } catch (e: Exception) {
            Logger.e("Failed to auto-start VoiceService", e)
        }
    }

    fun postVoiceCommand(command: String) {
        _commandFlow.tryEmit(command)
    }

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
        if (ExclusiveBrainLeaseState.isActive() || secureBrowserModelLease.isActive()) return
        val path = lastLlmPath ?: return
        if (orchestrator.isLlmLoaded()) return
        val spec = BrainModelRegistry.GEMMA_4_E2B
        Logger.i("UnoOneApplication: reloading ${spec.displayName} after memory pressure")
        appScope.launch {
            val result = orchestrator.loadLlmModel(path, spec)
            if (result is Result.Success) {
                Logger.i("UnoOneApplication: ${spec.displayName} reloaded from $path")
            } else {
                Logger.w(
                    "UnoOneApplication: ${spec.displayName} reload failed: " +
                        (result as? Result.Error)?.message
                )
            }
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
