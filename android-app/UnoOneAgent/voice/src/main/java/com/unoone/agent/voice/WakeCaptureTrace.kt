package com.unoone.agent.voice

import com.unoone.agent.core.latency.*
import com.unoone.agent.core.runtime.VoiceAdmissionTicket
import com.unoone.agent.core.voice.VoiceIngress

/** Immutable authority is cheap and unconditional; diagnostic storage starts only at ASR admission. */
internal object WakeCaptureTrace {
    fun captured(ingress: VoiceIngress) = VoiceAdmissionTicket.captured(ingress)
    fun admit(capture: VoiceAdmissionTicket, generation: Long, recorder: LatencyRecorder): VoiceAdmissionTicket {
        if (capture.generation != generation) return capture
        val start = capture.captureStartMono
        val token = if (start > 0 && start <= Long.MAX_VALUE / 1_000_000L)
            recorder.beginAt(LatencyOrigin.WAKE, start * 1_000_000L) else null
        recorder.bindRequest(capture.requestId, token)
        // This is the retained PCM interval boundary, not human speech onset.
        if (token != null) recorder.markAt(token, LatencyStage.MIC_REQUEST, start * 1_000_000L)
        return capture.copy(latencyToken = token)
    }
}
