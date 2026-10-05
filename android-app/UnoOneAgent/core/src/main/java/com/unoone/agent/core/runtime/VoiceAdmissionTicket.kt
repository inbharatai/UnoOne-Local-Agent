package com.unoone.agent.core.runtime

/** Immutable capture ownership; never assign a new generation to an awaited transcript. */
data class VoiceAdmissionTicket(val text: String, val generation: Long) {
    fun isCurrent(): Boolean = generation == GlobalTaskCancellation.generation
}
