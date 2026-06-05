package com.unoone.agent

import android.app.Application
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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

    companion object {
        lateinit var appContext: Context
            private set
    }
}