package com.unoone.agent.voice

/** Task busy is a dispatch restriction, never a physical microphone claim. */
object PassiveMonitoringPolicy {
    fun canCapture(speaking: Boolean, aecAvailable: Boolean, aecEnabled: Boolean,
                   callActive: Boolean, foregroundClaimed: Boolean): Boolean =
        EmergencyStopPolicy.canListen(speaking, aecAvailable, aecEnabled, callActive, foregroundClaimed)
    fun ordinaryAllowed(busy: Boolean, speaking: Boolean, foregroundClaimed: Boolean): Boolean =
        !busy && !speaking && !foregroundClaimed
    fun stopOnly(busy: Boolean, speaking: Boolean): Boolean = busy || speaking
    fun foregroundIdle(admissionOrDrainClaimed: Boolean, sessionOwned: Boolean): Boolean =
        !admissionOrDrainClaimed && !sessionOwned
}
