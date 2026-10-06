package com.unoone.agent.core.runtime

import com.unoone.agent.core.voice.VoiceIngress
import com.unoone.agent.core.voice.UnderlyingAppEvidence
import com.unoone.agent.core.latency.LatencyToken

/** Immutable capture ownership. Missing provenance is UNKNOWN, never freshened at delivery. */
data class VoiceAdmissionTicket(
    val text: String,
    val generation: Long,
    val requestId: String = "UNKNOWN",
    val captureStartMono: Long = 0L,
    val underlyingAppEvidence: UnderlyingAppEvidence? = null,
    val liveReviewId: String? = null,
    val latencyToken: LatencyToken? = null
) {
    fun isCurrent(): Boolean = generation == GlobalTaskCancellation.generation
    fun toIngress(): VoiceIngress = VoiceIngress(requestId, text, generation, captureStartMono,
        underlyingAppEvidence, liveReviewId.takeIf { requestId != "UNKNOWN" && captureStartMono > 0 })
    companion object {
        fun captured(ingress: VoiceIngress, trace: LatencyToken? = null) = VoiceAdmissionTicket(
            ingress.transcript, ingress.captureGlobalGeneration, ingress.requestId,
            ingress.captureStartMono, ingress.underlyingAppEvidence, ingress.liveReviewId, trace)
    }
}
