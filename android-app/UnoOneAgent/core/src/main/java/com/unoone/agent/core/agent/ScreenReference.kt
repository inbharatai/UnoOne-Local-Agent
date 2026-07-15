package com.unoone.agent.core.agent

/**
 * Decides whether a command references the on-screen content, so the context snapshot knows whether
 * capturing accessibility screen text + running OCR is worth the latency + privacy cost.
 *
 * Pure + dependency-free so the gating rule is JVM-unit-testable without the orchestrator or
 * Android. **Conservative**: when uncertain, capture (return `true`) — a screen-dependent planner
 * must never be starved of the screen text it needs. Non-screen commands ("explain god",
 * "draft an email", "search my notes") return `false` so the snapshot skips the accessibility/OCR
 * grab entirely. After the CHAT lane ([IntentClassifier]) diverts question-shaped input, this gate
 * only matters for LLM-planned agent actions, but it remains a privacy + latency win for any
 * non-screen action that reaches the planner.
 */
object ScreenReference {

    /**
     * Words/phrases that indicate the command cares about what is on the screen. Camera/Blind-Aid
     * references ("detect", "scan", "barrier", "in front") are intentionally NOT listed — those use
     * the camera, not the foreground app's screen text, so capturing it would only waste latency.
     */
    private val SCREEN_SIGNALS: List<String> = listOf(
        "screen", "page", "this app", "visible", "read this", "read screen", "read the screen",
        "button", "field", "what's on", "what is on", "whats on", "ocr"
    )

    fun isScreenReferencing(command: String): Boolean {
        val lowered = command.lowercase()
        return SCREEN_SIGNALS.any { lowered.contains(it) }
    }
}