package com.unoone.agent.voice

import android.media.AudioManager

/**
 * Privacy gate for background microphone capture.
 *
 * Phone and VoIP calls own Android's voice channel. UnoOne must not record, transcribe, or react
 * while either call mode is active; doing so can capture the other participant and produces stale
 * wake transcripts from call audio.
 */
object VoiceCapturePolicy {
    fun isCallAudioActive(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION
}
