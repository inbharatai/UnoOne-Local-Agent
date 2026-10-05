package com.unoone.agent.voice

/** Fail-closed playback exception. An enabled OS AEC is not proof of acoustic cancellation. */
object EmergencyStopPolicy {
    fun canListen(agentSpeaking: Boolean, aecAvailable: Boolean, aecEnabled: Boolean,
                  callActive: Boolean, microphoneOwnedElsewhere: Boolean): Boolean =
        !callActive && !microphoneOwnedElsewhere && (!agentSpeaking || (aecAvailable && aecEnabled))

    fun accepts(text: String, agentSpeaking: Boolean, aecAvailable: Boolean, aecEnabled: Boolean,
                callActive: Boolean, microphoneOwnedElsewhere: Boolean): Boolean =
        canListen(agentSpeaking, aecAvailable, aecEnabled, callActive, microphoneOwnedElsewhere) &&
            VoiceControlPolicy.isStop(text) &&
            (!agentSpeaking || VoiceControlPolicy.payload(text) != text.trim())
}
