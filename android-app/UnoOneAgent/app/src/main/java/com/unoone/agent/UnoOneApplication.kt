package com.unoone.agent

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UnoOneApplication : Application() {

    // Expert: Master Orchestrator accessible from anywhere (Activity or Service)
    lateinit var orchestrator: AgentOrchestrator
        private set

    // Single shared VoiceModule — used by orchestrator, ViewModel, and FloatingAgentService
    lateinit var sharedVoiceModule: VoiceModule
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val voiceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VoiceService.ACTION_VOICE_COMMAND) {
                val command = intent.getStringExtra(VoiceService.EXTRA_COMMAND)
                if (!command.isNullOrBlank()) {
                    Logger.i("UnoOneApplication: Received background voice command: '$command'")
                    appScope.launch {
                        orchestrator.processCommand(command, com.unoone.agent.core.model.InputType.VOICE)
                    }
                }
            }
        }
    }

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

        // Register background voice command broadcast receiver securely
        val filter = IntentFilter(VoiceService.ACTION_VOICE_COMMAND)
        ContextCompat.registerReceiver(
            this,
            voiceCommandReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Expert: Start background services for hands-free and floating assistant
        try {
            VoiceService.start(this)
        } catch (e: Exception) {
            Logger.e("Failed to auto-start VoiceService", e)
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}