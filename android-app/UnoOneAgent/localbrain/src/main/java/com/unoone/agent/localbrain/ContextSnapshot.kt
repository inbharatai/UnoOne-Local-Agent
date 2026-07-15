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
    val activeSkills: List<String> = emptyList(),
    /** Last few commands the user issued (oldest→newest), for continuity/disambiguation. */
    val recentCommands: List<String> = emptyList(),
    /** Human-readable result of the most recent tool execution, if any. */
    val lastToolResult: String = "",
    /**
     * The user's currently selected voice/TTS language code (e.g. "en", "hi"), surfaced to the
     * planner so it can keep its reply in the user's language. Not a screen/action fact — it does
     * not by itself fix the response language (the prompt directive does that); it only tells the
     * model which language the user is currently using.
     */
    val voiceLanguage: String = ""
) {
    fun isEmpty(): Boolean =
        currentPackage.isBlank() &&
            currentActivity.isBlank() &&
            visibleText.isBlank() &&
            ocrText.isBlank() &&
            recentNotes.isEmpty() &&
            userMemory.isBlank() &&
            activeSkills.isEmpty() &&
            recentCommands.isEmpty() &&
            lastToolResult.isBlank() &&
            voiceLanguage.isBlank()
}
