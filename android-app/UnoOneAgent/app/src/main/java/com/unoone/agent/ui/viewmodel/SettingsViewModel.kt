package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(context: Context) : ViewModel() {

    private val modelManager = ModelManager(context)
    private val appContext = context.applicationContext

    // 5D: Dark mode state persisted in SharedPreferences
    private val prefs = context.getSharedPreferences("unoone_settings", Context.MODE_PRIVATE)

    private val _modelStatuses = MutableStateFlow<List<ModelManager.ModelStatus>>(emptyList())
    val modelStatuses: StateFlow<List<ModelManager.ModelStatus>> = _modelStatuses.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    private val _darkMode = MutableStateFlow(prefs.getBoolean("dark_mode", false))
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _modelStatuses.value = modelManager.detectModels()
            _storageUsageMb.value = modelManager.getStorageUsageMb()
        }
    }

    fun ensureModelDirectories() {
        modelManager.ensureModelDirectories()
    }

    /** 5D: Toggle dark mode and persist in SharedPreferences */
    fun setDarkMode(enabled: Boolean) {
        _darkMode.value = enabled
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    /** 5E: Test STT by starting a recording, speaking, and transcribing */
    fun testStt(context: Context) {
        viewModelScope.launch {
            val voiceModule = VoiceModule(context)
            val initResult = voiceModule.initStt(context.getExternalFilesDir(null)?.absolutePath + "/models/sherpa-asr")
            if (initResult is Result.Error) {
                Logger.w("SettingsViewModel: STT test init failed: ${initResult.message}")
                // Try Android STT fallback
                voiceModule.startRecording(context, viewModelScope)
                return@launch
            }
            voiceModule.startRecording(context, viewModelScope)
        }
    }

    /** 5E: Test TTS by speaking a test phrase */
    fun testTts(context: Context) {
        val voiceModule = VoiceModule(context)
        voiceModule.speak("UnoOne is online and ready. Voice synthesis is working correctly.")
    }

    /** 5E: Clear logs via the action log DAO */
    fun clearLogs() {
        viewModelScope.launch {
            try {
                val db = com.unoone.agent.di.DatabaseProvider.getDatabase(appContext)
                db.actionLogDao().clearAll()
                Logger.i("SettingsViewModel: Logs cleared")
            } catch (e: Exception) {
                Logger.e("SettingsViewModel: Failed to clear logs", e)
            }
        }
    }

    /** 5E: Export logs to Downloads directory */
    fun exportLogs(context: Context): String {
        return try {
            val db = com.unoone.agent.di.DatabaseProvider.getDatabase(context)
            val logs = db.actionLogDao().getRecentSync(1000)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val sb = StringBuilder()
            sb.appendLine("UnoOne Action Log Export")
            sb.appendLine("Exported: ${sdf.format(Date())}")
            sb.appendLine("===\n")
            for (log in logs) {
                sb.appendLine("[${sdf.format(Date(log.createdAt))}] ${log.status.uppercase()} - ${log.selectedTool}")
                sb.appendLine("  Input: ${log.inputText}")
                if (log.errorMessage != null) sb.appendLine("  Error: ${log.errorMessage}")
                sb.appendLine()
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "unoone_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
            file.writeText(sb.toString())
            "Exported ${logs.size} logs to ${file.absolutePath}"
        } catch (e: Exception) {
            Logger.e("SettingsViewModel: Failed to export logs", e)
            "Export failed: ${e.message}"
        }
    }
}