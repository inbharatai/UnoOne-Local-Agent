package com.unoone.agent

import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.model.compoundSteps
import kotlinx.serialization.json.JsonPrimitive

/** Admission classification only; never authorizes execution or substitutes for SafetyPipeline. */
object RetainedVoiceRules {
    // Actual ActionExecutor tools, not speculative aliases. send_whatsapp opens a DRAFT only.
    private val tools = setOf("create_note", "search_notes", "delete_notes", "delete_all_notes",
        "draft_email", "send_whatsapp", "open_calendar", "check_calendar", "open_calendar_insert",
        "voice_recording", "share_text", "open_dialer", "create_skill")
    private val compoundNavigation = setOf("open_app", "open_chrome", "open_camera")

    fun requiresDraftClarification(text: String): Boolean {
        val send = Regex("""^(?:please\s+)?send\b""", RegexOption.IGNORE_CASE)
        if (send.containsMatchIn(text.trim())) return true
        // Only split actual parser-owned compounds, never draft/share/note payloads.
        if (com.unoone.agent.localbrain.RuleBasedParser.parse(text)?.tool != "compound") return false
        return Regex("""\s+(?:and(?:\s+then)?|then|और|फिर|aur|phir)\s+|\s*;\s*""", RegexOption.IGNORE_CASE)
            .split(text).any { send.containsMatchIn(it.trim()) }
    }

    /** Global actions only; Back/Scroll remain on frozen app-scoped Native routing. */
    fun isGlobalNavigation(call: ToolCall): Boolean =
        call.tool == "system_control" &&
            call.args.keys.all { it in setOf("action", "target") } &&
            (call.args["action"] as? JsonPrimitive)?.let {
                it.isString && it.content in setOf("go_home", "open_recents", "open_notifications")
            } == true &&
            (call.args["target"] == null || call.args["target"] == JsonPrimitive(""))

    fun supports(call: ToolCall, depth: Int = 0): Boolean {
        if (depth > 3) return false
        if (call.tool == "compound") {
            val steps = runCatching { call.compoundSteps() }.getOrNull() ?: return false
            return steps.size in 2..3 && steps.all { supports(it, depth + 1) }
        }
        return isGlobalNavigation(call) || call.tool in tools || (depth > 0 && call.tool in compoundNavigation)
    }
}
