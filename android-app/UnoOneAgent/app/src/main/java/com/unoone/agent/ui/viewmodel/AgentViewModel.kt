package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.util.Logger
import com.unoone.agent.ui.components.ConfirmationLevel
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentViewModel(
    private val orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule
) : ViewModel() {

    val timelineSteps: StateFlow<List<TimelineStep>> = orchestrator.timelineSteps
    val isProcessing: StateFlow<Boolean> = orchestrator.isProcessing
    val isBlindAidActive: StateFlow<Boolean> = orchestrator.isBlindAidActive

    // Single shared VoiceModule instance — also used by the orchestrator for speak()
    val voiceModuleInstance: VoiceModule = voiceModule

    private var _isListening = false
    val isListening: Boolean get() = _isListening

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<Pair<String, ConfirmationLevel>?>(null)
    val pendingConfirmation: StateFlow<Pair<String, ConfirmationLevel>?> = _pendingConfirmation.asStateFlow()

    init {
        // Wire the shared VoiceModule into the orchestrator so both use the same instance
        orchestrator.setVoiceModule(voiceModuleInstance)

        voiceModuleInstance.onAmplitude = { amp ->
            _amplitude.value = amp
        }

        orchestrator.onConfirmationRequired = { message, callback ->
            val level = if (message.startsWith("SECURITY CHECK")) ConfirmationLevel.STRONG_CONFIRM
            else ConfirmationLevel.CONFIRM
            _pendingConfirmation.value = message to level
            confirmationCallback = callback
        }
    }

    private var confirmationCallback: ((Boolean) -> Unit)? = null

    fun startListening(context: Context) {
        if (_isListening || isProcessing.value) return
        viewModelScope.launch {
            val result = voiceModuleInstance.startRecording(context, viewModelScope)
            if (result is Result.Success) {
                _isListening = true
            } else if (result is Result.Error) {
                Logger.w("Failed to start recording: ${result.message}")
            }
        }
    }

    fun stopListening() {
        if (!_isListening) return
        _isListening = false
        _amplitude.value = 0f
        viewModelScope.launch {
            val result = voiceModuleInstance.stopAndTranscribe()
            if (result is Result.Success && result.data.isNotBlank()) {
                orchestrator.processCommand(result.data, InputType.VOICE)
            } else if (result is Result.Error) {
                Logger.e("Speech transcription failed: ${result.message}")
            }
        }
    }

    fun setBlindAidActive(active: Boolean) {
        // Route through processCommand so the full safety/permission pipeline is honored
        viewModelScope.launch {
            val command = if (active) "activate blind aid" else "deactivate blind aid"
            orchestrator.processCommand(command, InputType.TEXT)
        }
    }

    fun onTextCommand(text: String) {
        viewModelScope.launch {
            orchestrator.processCommand(text, InputType.TEXT)
        }
    }

    fun onQuickAction(label: String) {
        viewModelScope.launch {
            val command = when (label) {
                "Create Note" -> "Create a note"
                "Open Chrome" -> "Open Chrome"
                "Calendar" -> "Open calendar"
                "Open App" -> "Open WhatsApp"
                else -> label
            }
            orchestrator.processCommand(command, InputType.TEXT)
        }
    }

    fun respondToConfirmation(allowed: Boolean) {
        confirmationCallback?.invoke(allowed)
        confirmationCallback = null
        _pendingConfirmation.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Release the single shared VoiceModule instance
        voiceModuleInstance.release()
    }
}