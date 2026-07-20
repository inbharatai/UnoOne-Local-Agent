package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.core.model.Result
import com.unoone.agent.languagepacks.LanguagePackManager
import com.unoone.agent.languagepacks.LanguagePackOperationResult
import com.unoone.agent.languagepacks.LanguagePackStatus
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class LanguagePackRow(
    val id: String,
    val languageCode: String,
    val displayName: String,
    val nativeName: String,
    val version: String,
    val status: LanguagePackStatus,
    val installed: Boolean,
    val healthy: Boolean,
    val verified: Boolean,
    val downloadable: Boolean,
    val required: Boolean,
    val removable: Boolean,
    val active: Boolean,
    val notes: String,
    val healthSummary: String
)

data class LanguagePackProgress(
    val packId: String,
    val modelId: String,
    val percent: Int,
    val message: String
)

internal fun voiceLanguageActivationSucceeded(
    sttResult: Result<Unit>,
    ttsResult: Result<Unit>
): Boolean = sttResult is Result.Success && ttsResult is Result.Success

internal fun isExposedVoiceLanguage(languageCode: String): Boolean =
    VoiceLanguage.isSupported(languageCode.substringBefore('-').lowercase())

/** User-facing language-pack orchestration over the lower-level model installer. */
class LanguagePacksViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val manager = LanguagePackManager(appContext)
    private val prefs = appContext.getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)

    private val _rows = MutableStateFlow<List<LanguagePackRow>>(emptyList())
    val rows: StateFlow<List<LanguagePackRow>> = _rows.asStateFlow()

    private val _busyPackId = MutableStateFlow<String?>(null)
    val busyPackId: StateFlow<String?> = _busyPackId.asStateFlow()

    private val _progress = MutableStateFlow<LanguagePackProgress?>(null)
    val progress: StateFlow<LanguagePackProgress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val activeCode = VoiceLanguage.normalize(prefs.getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT))
            _rows.value = manager.states()
                .filter { state -> isExposedVoiceLanguage(state.descriptor.languageCode) }
                .map { state ->
                    val descriptor = state.descriptor
                    val runtimeCode = descriptor.languageCode.substringBefore('-').lowercase()
                    LanguagePackRow(
                        id = descriptor.id,
                        languageCode = descriptor.languageCode,
                        displayName = descriptor.displayName,
                        nativeName = descriptor.nativeName,
                        version = descriptor.version,
                        status = descriptor.status,
                        installed = state.installed,
                        healthy = state.healthy,
                        verified = state.verified,
                        downloadable = descriptor.downloadable,
                        required = descriptor.required,
                        removable = descriptor.removable,
                        active = runtimeCode == activeCode,
                        notes = descriptor.notes,
                        healthSummary = when {
                            descriptor.requiredModelIds.isEmpty() -> "No qualified model files configured"
                            state.healthy && state.verified -> "Installed and hash-verified"
                            state.healthy -> "Installed; one or more files are not hash-verified"
                            state.unhealthyModelIds.isNotEmpty() -> "Repair required: ${state.unhealthyModelIds.joinToString()}"
                            state.missingModelIds.isNotEmpty() -> "Missing: ${state.missingModelIds.joinToString()}"
                            else -> "Not installed"
                        }
                    )
                }
        }
    }

    fun install(packId: String) {
        if (_busyPackId.value != null) return
        _busyPackId.value = packId
        _message.value = null
        viewModelScope.launch {
            val result = manager.install(packId) { modelId, percent, text ->
                _progress.value = LanguagePackProgress(packId, modelId, percent, text)
            }
            _progress.value = null
            _busyPackId.value = null
            _message.value = when (result) {
                is LanguagePackOperationResult.Success -> result.message
                is LanguagePackOperationResult.Failure -> result.message
            }
            refresh()
        }
    }

    fun uninstall(packId: String) {
        if (_busyPackId.value != null) return
        _busyPackId.value = packId
        _message.value = null
        viewModelScope.launch {
            val result = manager.uninstall(packId)
            _busyPackId.value = null
            _message.value = when (result) {
                is LanguagePackOperationResult.Success -> result.message
                is LanguagePackOperationResult.Failure -> result.message
            }
            refresh()
        }
    }

    fun activate(packId: String) {
        if (_busyPackId.value != null) return
        viewModelScope.launch {
            val state = manager.state(packId)
            if (state == null) {
                _message.value = "Unknown language pack"
                return@launch
            }
            if (!state.healthy) {
                _message.value = "${state.descriptor.displayName} cannot be activated until its required STT/TTS models pass health checks."
                return@launch
            }
            val runtimeCode = state.descriptor.languageCode.substringBefore('-').lowercase()
            if (!VoiceLanguage.isSupported(runtimeCode)) {
                _message.value = "${state.descriptor.displayName} is catalogued but the current voice runtime adapter is not implemented yet."
                return@launch
            }

            val sharedVoice = (appContext as? com.unoone.agent.UnoOneApplication)?.sharedVoiceModule
            if (sharedVoice != null) {
                val previousCode = VoiceLanguage.normalize(
                    prefs.getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
                )
                val base = (appContext.getExternalFilesDir(null)?.absolutePath
                    ?: appContext.filesDir.absolutePath) + "/models"
                val (sttResult, ttsResult) = withContext(Dispatchers.IO) {
                    sharedVoice.reinitForLanguage(base, runtimeCode)
                }
                val activated = voiceLanguageActivationSucceeded(sttResult, ttsResult)
                _message.value = if (activated) {
                    prefs.edit(commit = true) {
                        putString(VoiceLanguage.PREF_KEY, runtimeCode)
                    }
                    VoiceService.reinitLanguage(appContext)
                    "${state.descriptor.displayName} activated for offline STT and TTS."
                } else {
                    withContext(Dispatchers.IO) {
                        sharedVoice.reinitForLanguage(base, previousCode)
                    }
                    val failure = (sttResult as? Result.Error)?.message
                        ?: (ttsResult as? Result.Error)?.message
                        ?: "unknown engine error"
                    "Language files passed health checks but the live voice engine failed to initialize: " +
                        failure
                }
            } else {
                _message.value = "UnoOne voice runtime is not available. Restart the app and try again."
            }
            refresh()
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
