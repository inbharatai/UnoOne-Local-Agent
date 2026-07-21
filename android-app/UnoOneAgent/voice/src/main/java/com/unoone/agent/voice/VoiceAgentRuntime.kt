package com.unoone.agent.voice

import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceAgentState {
    DISABLED,
    INITIALISING,
    WAKE_LISTENING,
    WAKE_DETECTED,
    COMMAND_LISTENING,
    PROCESSING,
    WAITING_FOR_CONFIRMATION,
    EXECUTING,
    VERIFYING,
    SPEAKING,
    PAUSED,
    ERROR_RECOVERY
}

/**
 * In-memory developer diagnostics. Private command content is never written to production logs or
 * persisted; disabling clears every transcript and pending field.
 */
data class VoiceAgentDiagnostics(
    val state: VoiceAgentState = VoiceAgentState.INITIALISING,
    val rawTranscript: String = "",
    val stablePartialTranscript: String = "",
    val finalTranscript: String = "",
    val normalizedTranscript: String = "",
    val wakePhrase: String = "",
    val wakeConfidence: Float = 0f,
    val extractedCommand: String = "",
    val detectedLanguage: String = "",
    val preferredReplyLanguage: String = VoiceLanguage.DEFAULT,
    val parsedIntent: String = "",
    val intentConfidence: Float = 0f,
    val actionResult: String = "",
    val verificationResult: String = "",
    val errorCode: String = "",
    val recoveryAction: String = "",
    val transitionReason: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * One process-wide observable voice state machine shared by the service, UI and orchestrator.
 * Updates are synchronized so audio/service and inference threads cannot overwrite each other.
 */
object VoiceAgentRuntime {
    private val _diagnostics = MutableStateFlow(VoiceAgentDiagnostics())
    val diagnostics: StateFlow<VoiceAgentDiagnostics> = _diagnostics.asStateFlow()

    val state: VoiceAgentState get() = _diagnostics.value.state

    @Synchronized
    fun transition(next: VoiceAgentState, reason: String = "") {
        val previous = _diagnostics.value.state
        _diagnostics.value = _diagnostics.value.copy(
            state = next,
            transitionReason = reason.take(120),
            updatedAtMs = System.currentTimeMillis()
        )
        if (previous != next) Logger.i("VoiceAgentState: $previous -> $next")
    }

    @Synchronized
    fun recordWake(rawTranscript: String, match: WakePhraseMatch) {
        _diagnostics.value = _diagnostics.value.copy(
            rawTranscript = rawTranscript.take(500),
            finalTranscript = rawTranscript.take(500),
            normalizedTranscript = match.normalizedTranscript.take(500),
            wakePhrase = match.matchedPhrase,
            wakeConfidence = match.confidence,
            extractedCommand = match.command.take(500),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun recordCommand(
        rawTranscript: String,
        language: String,
        preferredReplyLanguage: String
    ) {
        _diagnostics.value = _diagnostics.value.copy(
            rawTranscript = rawTranscript.take(500),
            finalTranscript = rawTranscript.take(500),
            normalizedTranscript = WakePhraseNormalizer.normalize(rawTranscript).take(500),
            extractedCommand = WakePhrases.stripFromCommand(rawTranscript).take(500),
            detectedLanguage = language,
            preferredReplyLanguage = preferredReplyLanguage,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun recordIntent(intent: String, confidence: Float) {
        _diagnostics.value = _diagnostics.value.copy(
            parsedIntent = intent,
            intentConfidence = confidence.coerceIn(0f, 1f),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun recordOutcome(actionResult: String, verificationResult: String = "") {
        _diagnostics.value = _diagnostics.value.copy(
            actionResult = actionResult.take(500),
            verificationResult = verificationResult.take(500),
            errorCode = "",
            recoveryAction = "",
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun recordError(code: String, recoveryAction: String) {
        _diagnostics.value = _diagnostics.value.copy(
            errorCode = code.take(80),
            recoveryAction = recoveryAction.take(240),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun clearForDisable() {
        _diagnostics.value = VoiceAgentDiagnostics(state = VoiceAgentState.DISABLED)
    }
}
