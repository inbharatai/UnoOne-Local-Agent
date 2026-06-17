package com.unoone.agent.localbrain

/**
 * A snapshot of everything the local LLM should know before planning an action.
 * All fields are intentionally simple (strings / lists) so they serialize cleanly
 * into a text prompt without leaking full objects to the model.
 */
data class ContextSnapshot(
    val currentPackage: String = "",
    val currentActivity: String = "",
    val visibleText: String = "",
    val ocrText: String = "",
    val recentNotes: List<String> = emptyList(),
    val userMemory: String = "",
    val activeSkills: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        currentPackage.isBlank() &&
            currentActivity.isBlank() &&
            visibleText.isBlank() &&
            ocrText.isBlank() &&
            recentNotes.isEmpty() &&
            userMemory.isBlank() &&
            activeSkills.isEmpty()
}
