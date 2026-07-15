package com.unoone.agent.core.agent

import com.unoone.agent.core.model.AgentStatus

/**
 * Eyes-free (WS2) step narration. Maps a timeline step to a short spoken phrase, or `null` when the
 * step should not be spoken. Pure + JVM-tested; the actual TTS call lives in `AgentOrchestrator`.
 *
 * Coordination rule (do NOT double-speak): the final answer is spoken directly by the FAST_ACTION /
 * CHAT / AGENT_ACTION lanes when `inputType == VOICE`, so [AgentStatus.SPEAKING] and
 * [AgentStatus.DONE] return `null` here. Narration covers only the **intermediate** action
 * milestones ("Opening Chrome", "Creating a note", "Checking safety") and the **terminal** failure
 * feedback ("Action blocked for security.", "Needs system access for ...") that a blind user
 * otherwise would not hear. [AgentStatus.UNDERSTANDING] ("Thinking") is intentionally silent — it
 * is chatty mid-stream and the CHAT lane already speaks the streamed answer.
 */
object NarrationPolicy {

    /**
     * Friendly spoken phrase for a canonical tool name, or `null` when the tool IS the answer
     * (e.g. `speak_response`) or is not a user-meaningful action (e.g. `compound`). Pure data.
     */
    fun toolPhrase(tool: String): String? = when (tool) {
        "open_chrome" -> "Opening Chrome"
        "open_calendar" -> "Opening calendar"
        "check_calendar" -> "Checking calendar"
        "open_camera" -> "Opening camera"
        "detect_objects" -> "Starting blind aid"
        "deactivate_blind_aid" -> "Stopping blind aid"
        "create_note" -> "Creating a note"
        "search_notes" -> "Searching notes"
        "delete_notes" -> "Deleting notes"
        "delete_all_notes" -> "Deleting all notes"
        "open_app" -> "Opening app"
        "open_url" -> "Opening link"
        "open_dialer" -> "Opening dialer"
        "draft_email" -> "Drafting email"
        "send_whatsapp" -> "Opening WhatsApp"
        "share_text" -> "Sharing"
        "read_screen" -> "Reading screen"
        "ocr_screen" -> "Reading screen"
        "describe_scene" -> "Describing scene"
        "system_control" -> "Controlling the screen"
        "web_search" -> "Searching the web"
        "summarize_text" -> "Summarizing"
        "create_skill" -> "Creating skill"
        "export_data" -> "Exporting data"
        "voice_recording" -> null   // capture itself, not a speakable action
        "speak_response" -> null     // the answer itself; the lane speaks it
        "compound" -> null
        else -> null
    }

    /**
     * Extracts the canonical tool name embedded in a step detail. Recognizes the shapes used by
     * `AgentOrchestrator`: `"Action: open_chrome"`, `"Executing open_chrome..."`,
     * `"Follow-up: open_chrome"`. Returns `null` when no recognizable tool is present.
     */
    private fun extractTool(detail: String): String? {
        for (prefix in listOf("Action: ", "Executing ", "Follow-up: ")) {
            if (detail.contains(prefix)) {
                val rest = detail.substringAfter(prefix)
                    .trim()
                    .substringBefore("...")
                    .substringBefore(" ")
                    .trim()
                if (rest.matches(Regex("[a-z][a-z_]*"))) return rest
            }
        }
        return null
    }

    /**
     * The spoken phrase for a timeline step, or `null` if it should stay silent.
     */
    fun narrationFor(status: AgentStatus, label: String, detail: String): String? {
        return when (status) {
            AgentStatus.SAFETY_CHECK -> when {
                label == "Access Required" -> "I need access. $detail"
                label == "Confirmation Required" -> "Please confirm. $detail"
                label == "Safety Filter" -> "Checking safety"
                else -> null // "Safety Judge" is an internal escalation detail, not user-facing
            }
            AgentStatus.TOOL_SELECTED -> when {
                label == "Agent Plan" -> toolPhrase(extractTool(detail) ?: "") ?: "Planning"
                label == "Executing Skill" -> "Running skill $detail"
                label == "Skill Step" -> toolPhrase(detail)
                else -> null
            }
            AgentStatus.EXECUTING -> when {
                label == "Agent Active" -> toolPhrase(extractTool(detail) ?: "") ?: "Acting"
                label.startsWith("Compound Step") -> toolPhrase(detail) ?: "Acting"
                label == "Skill Step" -> toolPhrase(detail)
                label == "Recovering" -> "Brain dropped. Reloading"
                else -> null
            }
            AgentStatus.VERIFYING -> when (label) {
                "Verifying Outcome" -> "Verifying"
                "Recovered" -> "Brain reloaded"
                else -> null
            }
            AgentStatus.FAILED -> detail.takeIf { it.isNotBlank() }
            // Silent: the lane speaks the final answer; "Thinking" is chatty mid-stream.
            AgentStatus.SPEAKING,
            AgentStatus.DONE,
            AgentStatus.UNDERSTANDING,
            AgentStatus.IDLE,
            AgentStatus.LISTENING,
            AgentStatus.TRANSCRIBING -> null
        }
    }
}