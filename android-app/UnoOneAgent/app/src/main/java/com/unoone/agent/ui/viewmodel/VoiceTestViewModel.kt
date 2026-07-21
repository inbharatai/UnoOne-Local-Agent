package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceRuntimeState
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.WakePhraseMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Voice Test screen. Uses the **shared** application [VoiceModule] (the same instance
 * the orchestrator uses) so the test reflects real runtime state — unlike a throwaway VoiceModule.
 * Records for ~3s, transcribes via Sherpa, and shows text + confidence. Also speaks arbitrary text
 * to test TTS. Surfaces which engine is active (Sherpa / system fallback / unavailable).
 */
class VoiceTestViewModel(private val voiceModule: VoiceModule) : ViewModel() {

    /** Snapshot of the active engines. Refreshed on a cadence from the screen. */
    data class EngineState(
        val stt: VoiceRuntimeState,
        val tts: VoiceRuntimeState,
        val sttReady: Boolean,
        val ttsReady: Boolean,
        val systemFallbackAllowed: Boolean,
        val language: String
    )

    private val _engine = MutableStateFlow(
        EngineState(
            VoiceRuntimeState.UNAVAILABLE,
            VoiceRuntimeState.UNAVAILABLE,
            false,
            false,
            false,
            VoiceLanguage.DEFAULT
        )
    )
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private val _wakeMatch = MutableStateFlow("")
    val wakeMatch: StateFlow<String> = _wakeMatch.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Pull current @Volatile engine state from the shared VoiceModule into a Flow. */
    fun refresh() {
        _engine.value = EngineState(
            stt = voiceModule.sttState,
            tts = voiceModule.ttsState,
            sttReady = voiceModule.isSttInitialized(),
            ttsReady = voiceModule.isTtsInitialized(),
            systemFallbackAllowed = voiceModule.allowSystemSttFallback,
            language = voiceModule.currentLanguage()
        )
    }

    /**
     * Records for ~3 seconds then transcribes. Real Sherpa path: capture PCM, run ASR, read
     * confidence. If Sherpa is unavailable and the emergency fallback is disabled, surfaces a clear
     * "install the model" message instead of silently using cloud STT.
     */
    fun startSttTest(context: Context) {
        if (_isRecording.value) return
        viewModelScope.launch {
            _transcript.value = ""
            _confidence.value = 0f
            _wakeMatch.value = ""
            _message.value = "Listening… speak now"
            val start = voiceModule.startRecording(context, viewModelScope)
            if (start is Result.Error) {
                _message.value = start.message
                return@launch
            }
            _isRecording.value = true
            delay(3_000)
            _isRecording.value = false
            _message.value = "Transcribing…"
            val result = voiceModule.stopAndTranscribe()
            when (result) {
                is Result.Success -> {
                    _transcript.value = result.data
                    _confidence.value = voiceModule.lastSttConfidence
                    val wake = WakePhraseMatcher.match(result.data)
                    _wakeMatch.value = if (wake == null) {
                        "No conservative wake phrase matched this transcript."
                    } else {
                        "Wake matched: ${wake.matchedPhrase} " +
                            "(${(wake.confidence * 100).toInt()}%); command: " +
                            wake.command.ifBlank { "[next utterance]" }
                    }
                    _message.value = if (result.data.isBlank()) {
                        "Heard nothing. Try again in a quieter spot."
                    } else {
                        "Heard: \"${result.data}\""
                    }
                }
                is Result.Error -> {
                    _message.value = "STT failed: ${result.message}"
                    Logger.w("VoiceTestViewModel: STT test failed: ${result.message}")
                }
            }
        }
    }

    fun cancelRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        viewModelScope.launch { voiceModule.stopAndTranscribe() }
        _message.value = "Cancelled"
    }

    /** Speak arbitrary text using the active TTS engine (Sherpa, else the emergency Android TTS). */
    fun speak(text: String) {
        if (text.isBlank()) {
            _message.value = "Type something to speak first."
            return
        }
        viewModelScope.launch {
            val result = voiceModule.speakAwait(
                text,
                VoiceLanguage.localeTag(voiceModule.currentLanguage())
            )
            _message.value = when (result) {
                is Result.Success -> "Spoke: \"$text\""
                is Result.Error -> "TTS failed: ${result.message}"
            }
            refresh()
        }
    }

    fun stopSpeaking() {
        voiceModule.stopSpeaking()
        _message.value = "Stopped"
    }

    fun consumeMessage() { _message.value = null }
}
