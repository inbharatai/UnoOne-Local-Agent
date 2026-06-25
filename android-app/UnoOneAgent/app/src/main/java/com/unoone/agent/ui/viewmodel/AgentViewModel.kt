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
import com.unoone.agent.voice.VoiceRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Coarse device readiness shown as a chip on the Agent screen:
 * - [OFFLINE] (green): Sherpa STT+TTS active, no system fallback, brain loaded.
 * - [LIMITED] (amber): running on the emergency Android STT/TTS fallback.
 * - [NO_MODEL] (red): Sherpa STT/TTS or the LLM brain is unavailable.
 */
enum class OfflineMode { OFFLINE, LIMITED, NO_MODEL }

class AgentViewModel(
    private val orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule
) : ViewModel() {

    companion object {
        /** STT results below this trigger one retry prompt ("please repeat"). */
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f
    }

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

    // Offline-mode chip state. Polled by the screen (VoiceModule exposes @Volatile state, not a
    // Flow, so we refresh on a cadence and on lifecycle resume).
    private val _offlineMode = MutableStateFlow(OfflineMode.NO_MODEL)
    val offlineMode: StateFlow<OfflineMode> = _offlineMode.asStateFlow()

    // One-shot low-confidence retry: true while we're re-listening for a repeated utterance so
    // a second low-confidence result is not retried again (avoids infinite re-prompt loops).
    private var retryArmed = false

    /** Recompute the offline-mode chip from current engine + brain state. Call from the screen. */
    fun refreshOfflineMode() {
        val stt = voiceModuleInstance.sttState
        val tts = voiceModuleInstance.ttsState
        val llm = orchestrator.isLlmLoaded()
        _offlineMode.value = when {
            // Sherpa missing for STT or TTS, AND no brain → nothing works offline.
            stt == VoiceRuntimeState.UNAVAILABLE && tts == VoiceRuntimeState.UNAVAILABLE && !llm -> OfflineMode.NO_MODEL
            // Emergency system STT/TTS fallback in use anywhere → online-ish / limited.
            stt == VoiceRuntimeState.SYSTEM_FALLBACK || tts == VoiceRuntimeState.SYSTEM_FALLBACK -> OfflineMode.LIMITED
            // Sherpa STT/TTS active and no fallback, with the brain loaded → fully offline.
            stt == VoiceRuntimeState.SHERPA && tts == VoiceRuntimeState.SHERPA && llm -> OfflineMode.OFFLINE
            // Sherpa voice available but brain not yet loaded → still offline voice, but flag limited.
            stt == VoiceRuntimeState.SHERPA || tts == VoiceRuntimeState.SHERPA -> OfflineMode.LIMITED
            else -> OfflineMode.NO_MODEL
        }
    }

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
                val confidence = voiceModuleInstance.lastSttConfidence
                // Low-confidence retry: ask the user to repeat once, then re-listen. Don't loop.
                if (confidence < LOW_CONFIDENCE_THRESHOLD && !retryArmed) {
                    retryArmed = true
                    voiceModuleInstance.speak("Sorry, I didn't catch that clearly. Could you please repeat?")
                    Logger.i("AgentViewModel: Low STT confidence (${"%.2f".format(confidence)}); re-listening once.")
                    // Re-listen using the app-scoped context (a singleton) rather than holding an
                    // Activity Context across the coroutine — avoids a ViewModel context leak.
                    startListening(com.unoone.agent.UnoOneApplication.appContext)
                    return@launch
                }
                retryArmed = false
                orchestrator.processCommand(result.data, InputType.VOICE)
            } else if (result is Result.Error) {
                retryArmed = false
                Logger.e("Speech transcription failed: ${result.message}")
            } else {
                retryArmed = false
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