package com.unoone.agent.diagnostics

import com.unoone.agent.voice.VoiceLatency

/** UI owner supplies deliberate consent/export actions. No automatic persistence or upload. */
object LatencyExport {
    fun setCollectionConsent(enabled: Boolean) { VoiceLatency.recorder.enabled = enabled }
    fun clear() = VoiceLatency.recorder.clear()
    fun metadataJson(): String = VoiceLatency.recorder.exportJson()
    const val PRIVACY_NOTICE = "Opt-in local metadata only; up to 256 traces and 128 events per trace. No transcript, audio, image, prompt or task identifiers. Memory-only: app death loses traces. Playback completion is a wait proxy, not first audible sound."
}
