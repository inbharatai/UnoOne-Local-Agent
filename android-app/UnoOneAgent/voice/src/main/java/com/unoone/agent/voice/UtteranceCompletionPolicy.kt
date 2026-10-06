package com.unoone.agent.voice

/** A final STT result is not evidence that the speaker finished the utterance. */
object UtteranceCompletionPolicy {
    enum class TerminalReason { SILENCE, MANUAL_DONE, DURATION_CUTOFF, CANCELLED }
    const val SHORTER_CUE = "That recording reached its limit. Please say a shorter command."
    fun endpointReason(completed: Boolean): TerminalReason =
        if (completed) TerminalReason.SILENCE else TerminalReason.DURATION_CUTOFF
    fun eligible(reason: TerminalReason, transcript: String?): Boolean =
        !transcript.isNullOrBlank() &&
            (reason == TerminalReason.SILENCE || reason == TerminalReason.MANUAL_DONE)
}
