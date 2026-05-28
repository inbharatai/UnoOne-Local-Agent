package com.unoone.agent

import android.app.Application
import android.content.Context
import com.unoone.agent.core.util.Logger
import com.unoone.agent.di.DatabaseProvider
import com.unoone.agent.voice.VoiceService

class UnoOneApplication : Application() {

    // Expert: Master Orchestrator accessible from anywhere (Activity or Service)
    lateinit var orchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        Logger.i("UnoOne starting up - Expert Mode")
        appContext = applicationContext
        
        val db = DatabaseProvider.getDatabase(this)
        // Correcting the orchestrator initialization with skillDao
        orchestrator = AgentOrchestrator(
            this,
            db.noteDao(),
            db.actionLogDao(),
            db.memoryDao(),
            db.skillDao()
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
