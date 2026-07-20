package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.core.model.Result
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.safety.SecurityLevel
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
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
    private val runtimeController = appContext as? UnoOneApplication
    private val fallbackAgentEnabled = MutableStateFlow(AgentRuntimeGate.isEnabled())
    val isAgentEnabled: StateFlow<Boolean> =
        runtimeController?.isAgentEnabled ?: fallbackAgentEnabled.asStateFlow()

    private val _modelStatuses = MutableStateFlow<List<ModelManager.ModelStatus>>(emptyList())
    val modelStatuses: StateFlow<List<ModelManager.ModelStatus>> = _modelStatuses.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    private val _darkMode = MutableStateFlow(prefs.getBoolean("dark_mode", false))
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    // Offline voice language, persisted in the same unoone_settings store. Default English.
    private val _voiceLanguage = MutableStateFlow(VoiceLanguage.normalize(prefs.getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)))
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()
    private val voiceLanguagePreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == VoiceLanguage.PREF_KEY) {
                _voiceLanguage.value = VoiceLanguage.normalize(
                    preferences.getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
                )
            }
        }

    // User-selectable agent security posture (Settings → Security Level). Default STANDARD so the
    // app never silently weakens safety. The orchestrator re-reads this per tool call, so a change
    // here takes effect on the next command without a restart.
    private val _securityLevel = MutableStateFlow(SecurityLevel.current(context))
    val securityLevel: StateFlow<SecurityLevel> = _securityLevel.asStateFlow()

    init {
        // Voice commands and the Offline Languages screen can both change this preference outside
        // SettingsViewModel. Observing the source of truth keeps the landing-page language chip in
        // sync with the live STT/TTS engines instead of displaying the previous language.
        prefs.registerOnSharedPreferenceChangeListener(voiceLanguagePreferenceListener)
        refresh()
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(voiceLanguagePreferenceListener)
        super.onCleared()
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

    fun disableAgent() {
        runtimeController?.disableAgent()
        fallbackAgentEnabled.value = false
    }

    fun enableAgent() {
        runtimeController?.enableAgent()
        fallbackAgentEnabled.value = true
    }

    /**
     * Set the agent security level (STANDARD / RELAXED / OFF) and persist it. The orchestrator
     * re-reads the pref on the next tool call, so the change is live without a restart.
     */
    fun setSecurityLevel(level: SecurityLevel) {
        _securityLevel.value = level
        SecurityLevel.set(appContext, level)
        Logger.i("SettingsViewModel: security level set to ${level.name}")
    }

    /**
     * Select the offline voice language (English or Hindi), persist it, and ask the live
     * VoiceService + shared VoiceModule to rebuild their STT/TTS engines for the new language so
     * the change takes effect without an app restart. Unsupported codes are normalized to English.
     */
    fun setVoiceLanguage(code: String) {
        val normalized = VoiceLanguage.normalize(code)
        _voiceLanguage.value = normalized
        prefs.edit { putString(VoiceLanguage.PREF_KEY, normalized) }
        Logger.i("SettingsViewModel: voice language set to '$normalized'")
        // Rebuild engines for the new language. VoiceService owns the wake-word loop path; the
        // shared VoiceModule owns the mic-button / VoiceTest path. Both read the pref we just wrote.
        // reinitForLanguage is heavy blocking model I/O — it MUST NOT run on viewModelScope's default
        // Main dispatcher, or switching to a larger Indic/Whisper language freezes the UI (ANR).
        VoiceService.reinitLanguage(appContext)
        val shared = (appContext as? com.unoone.agent.UnoOneApplication)?.sharedVoiceModule
        if (shared != null) {
            viewModelScope.launch {
                val modelBaseDir = (appContext.getExternalFilesDir(null)?.absolutePath
                    ?: appContext.filesDir.absolutePath) + "/models"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    shared.reinitForLanguage(modelBaseDir, normalized)
                }
            }
        }
    }

    /** 5E: Test STT by starting a recording, speaking, and transcribing (active language). */
    fun testStt(context: Context) {
        viewModelScope.launch {
            // Use the shared VoiceModule (already initialized for the active language at startup);
            // fall back to a fresh module if the app instance isn't available.
            val voiceModule = (context.applicationContext as? com.unoone.agent.UnoOneApplication)?.sharedVoiceModule
                ?: VoiceModule(context).also {
                    val base = context.getExternalFilesDir(null)?.absolutePath + "/models"
                    it.reinitForLanguage(base)
                }
            if (!voiceModule.isSttInitialized()) {
                val base = context.getExternalFilesDir(null)?.absolutePath + "/models"
                voiceModule.reinitForLanguage(base)
            }
            val startResult = voiceModule.startRecording(context, viewModelScope)
            if (startResult is Result.Error) {
                Logger.w("SettingsViewModel: STT test start failed: ${startResult.message}")
            }
        }
    }

    /** 5E: Test TTS by speaking a test phrase */
    fun testTts(context: Context) {
        val voiceModule = (context.applicationContext as? UnoOneApplication)?.sharedVoiceModule
            ?: VoiceModule(context)
        val language = VoiceLanguage.normalize(prefs.getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT))
        viewModelScope.launch {
            if (!voiceModule.isTtsInitialized()) {
                val base = context.getExternalFilesDir(null)?.absolutePath + "/models"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    voiceModule.reinitForLanguage(base, language)
                }
            }
            voiceModule.speakAwait(
                VoiceLanguage.testPhrase(language),
                VoiceLanguage.localeTag(language)
            )
        }
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
