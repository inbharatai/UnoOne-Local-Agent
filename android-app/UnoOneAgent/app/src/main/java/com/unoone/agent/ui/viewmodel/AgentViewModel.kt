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
import java.util.concurrent.atomic.AtomicReference

class AgentViewModel(
    private val orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule
) : ViewModel() {

    val timelineSteps: StateFlow<List<TimelineStep>> = orchestrator.timelineSteps
    val isProcessing: StateFlow<Boolean> = orchestrator.isProcessing
    val isBlindAidActive: StateFlow<Boolean> = orchestrator.isBlindAidActive

    // Single shared VoiceModule instance — also used by the orchestrator for speak()
    val voiceModuleInstance: VoiceModule = voiceModule

    // Thread-safe listening state, observable by Compose
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<Pair<String, ConfirmationLevel>?>(null)
    val pendingConfirmation: StateFlow<Pair<String, ConfirmationLevel>?> = _pendingConfirmation.asStateFlow()

    // Thread-safe confirmation callback using AtomicReference
    private val confirmationCallback = AtomicReference<((Boolean) -> Unit)?>(null)

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
            confirmationCallback.set(callback)
        }
    }

    fun startListening(context: Context) {
        if (_isListening.value || isProcessing.value) return
        viewModelScope.launch {
            val result = voiceModuleInstance.startRecording(context, viewModelScope)
            if (result is Result.Success) {
                _isListening.value = true
            } else if (result is Result.Error) {
                Logger.w("Failed to start recording: ${result.message}")
            }
        }
    }

    fun stopListening() {
        if (!_isListening.value) return
        _isListening.value = false
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
        // Direct toggle when the user explicitly presses the UI button — no safety
        // confirmation needed because the user initiated this action deliberately.
        // Voice/text commands like "activate blind aid" still go through the full
        // safety pipeline via processCommand.
        orchestrator.setBlindAidActive(active)
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
        confirmationCallback.getAndSet(null)?.invoke(allowed)
        _pendingConfirmation.value = null
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT release voiceModuleInstance here — it is the application-scoped shared
        // instance owned by UnoOneApplication. Releasing it would destroy the VoiceModule
        // used by the orchestrator, broadcast receiver, and FloatingAgentService.
    }
}