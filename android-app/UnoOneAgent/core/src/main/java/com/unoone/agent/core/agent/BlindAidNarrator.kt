package com.unoone.agent.core.agent

/**
 * Eyes-free (WS3): decides when and what the Blind Aid camera should speak as a periodic scene
 * summary ("In front of you: a chair, a desk, a person"), separate from the close-obstacle
 * warnings. Pure logic so the throttle + summary wording are JVM-testable without CameraX/ML Kit;
 * [com.unoone.agent.phonecontrol.BlindAidManager] calls this from its detection callback.
 *
 * Throttle model: a scene change (the set of distinct labels changed) may be narrated at most every
 * [DEFAULT_CHANGE_INTERVAL_MS]; a steady, unchanged scene is re-narrated at most every
 * [DEFAULT_STEADY_INTERVAL_MS] so a blind user still gets a periodic "still here" update without
 * per-frame chatter. Label flicker is absorbed by the change interval rather than triggering a
 * speak on every frame. Quiet mode suppresses all scene narration (obstacle warnings are handled
 * separately by the manager).
 */
object BlindAidNarrator {

    /** Min gap between scene narrations when the label set has changed. */
    const val DEFAULT_CHANGE_INTERVAL_MS = 2_000L

    /** Min gap between scene narrations when the label set is unchanged (periodic re-narration). */
    const val DEFAULT_STEADY_INTERVAL_MS = 6_000L

    /**
     * Build a spoken scene summary from the detected labels. Returns "" when there is nothing
     * meaningful to say (no labels, or only the generic "obstacle" fallback). Distinct labels are
     * lowercased and preceded by an article ("a chair", "an apple"). The generic "obstacle" label
     * is dropped because it carries no scene information for a blind user.
     */
    fun sceneSummary(labels: Collection<String>): String {
        val distinct = labels.mapNotNull { it.trim().lowercase().ifBlank { null } }
            .filter { it != "obstacle" }
            .distinct()
        if (distinct.isEmpty()) return ""
        return "In front of you: " + distinct.joinToString(", ") { withArticle(it) } + "."
    }

    /**
     * Decide whether a scene narration should fire now. Returns false in quiet mode, when the
     * current label set is empty/only "obstacle", or when not enough time has elapsed since the last
     * narration ([lastNarrationMs]). A label-set change uses the shorter [changeIntervalMs]; an
     * unchanged scene uses the longer [steadyIntervalMs].
     */
    fun shouldNarrateScene(
        nowMs: Long,
        lastNarrationMs: Long,
        lastLabels: Set<String>,
        currentLabels: Set<String>,
        quietMode: Boolean,
        changeIntervalMs: Long = DEFAULT_CHANGE_INTERVAL_MS,
        steadyIntervalMs: Long = DEFAULT_STEADY_INTERVAL_MS
    ): Boolean {
        if (quietMode) return false
        val current = normalize(currentLabels)
        if (current.isEmpty()) return false
        val changed = current != normalize(lastLabels)
        val interval = if (changed) changeIntervalMs else steadyIntervalMs
        return nowMs - lastNarrationMs >= interval
    }

    /** Normalize a label set the same way [sceneSummary] / [shouldNarrateScene] do. */
    fun normalize(labels: Set<String>): Set<String> =
        labels.mapNotNull { it.trim().lowercase().ifBlank { null } }
            .filter { it != "obstacle" }
            .toSet()

    private fun withArticle(noun: String): String =
        if (noun.startsWithVowel()) "an $noun" else "a $noun"

    private fun String.startsWithVowel(): Boolean =
        isNotEmpty() && first().lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')
}