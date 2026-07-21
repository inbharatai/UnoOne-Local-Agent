package com.unoone.agent.core.eval

/**
 * One calibration case: a prompt, the tool the brain should select, and argument values it should
 * extract. The device-time harness converts tool quality from a subjective impression into exact
 * tool-match and argument-match scores for the qualified E4B artifact and backend configuration.
 *
 * [expectedArgs] lists only the arguments worth checking. A blank expected value means the argument
 * must be present and non-empty when that tool is selected.
 */
data class EvalCase(
    val id: String,
    val prompt: String,
    val expectedTool: String,
    val expectedArgs: Map<String, String> = emptyMap(),
    val notes: String = ""
)

/**
 * Fixed smoke-calibration set for physical-device evaluation.
 *
 * Application-level deterministic routing should resolve many of these commands before E4B is used.
 * The direct planner harness still includes them to verify that the model emits canonical tools and
 * grounded arguments when the AGENT_ACTION lane or Page Agent requires planning.
 */
object EvalPromptSet {

    val cases: List<EvalCase> = listOf(
        EvalCase("nav-chrome", "open chrome", "open_chrome"),
        EvalCase("nav-app", "open whatsapp", "open_app", mapOf("app_name" to "whatsapp")),
        EvalCase("nav-gmail", "open gmail", "open_app", mapOf("app_name" to "gmail")),
        EvalCase("nav-camera", "open the camera", "open_camera"),
        EvalCase("nav-dialer", "open the dialer with number 5551234", "open_dialer", mapOf("number" to "5551234")),
        EvalCase(
            "note-create",
            "create a note titled shopping list with content milk and bread",
            "create_note",
            mapOf("title" to "shopping list", "content" to "milk and bread")
        ),
        EvalCase("note-search", "search my notes for the trip", "search_notes", mapOf("query" to "trip")),
        EvalCase("note-delete-one", "delete notes about the meeting", "delete_notes", mapOf("query" to "meeting")),
        EvalCase("note-delete-all", "delete all my notes", "delete_all_notes"),
        EvalCase(
            "note-delete-paraphrased",
            "wipe everything in my notes",
            "delete_all_notes",
            notes = "Paraphrased destructive action; native safety must still block or strongly confirm execution."
        ),
        EvalCase("screen-read", "what is on my screen right now", "read_screen"),
        EvalCase("web-search", "search the web for climate news", "web_search", mapOf("query" to "climate news")),
        EvalCase(
            "url-open",
            "open the website https://example.com",
            "open_url",
            mapOf("url" to "https://example.com")
        ),
        EvalCase(
            "summarize",
            "summarize this text: the quick brown fox jumps over the lazy dog",
            "summarize_text",
            mapOf("text" to "quick brown fox")
        ),
        EvalCase(
            "whatsapp-draft",
            "prepare a whatsapp message to 1234567890 saying I will be late",
            "send_whatsapp",
            mapOf("number" to "1234567890", "message" to "late"),
            notes = "Legacy tool name; executor must prepare a reviewable draft and never press Send."
        ),
        EvalCase(
            "email-draft",
            "draft an email to boss@example.com with subject quarterly review and body please review the report",
            "draft_email",
            mapOf("to" to "boss@example.com", "subject" to "quarterly review", "body" to "review the report")
        ),
        EvalCase("calendar-check", "what is on my calendar today", "check_calendar"),
        EvalCase(
            "calendar-draft",
            "create a calendar event titled visa meeting at 2026-07-22T17:00:00+05:30",
            "open_calendar_insert",
            mapOf("title" to "visa meeting", "start_time" to "2026-07-22T17:00:00+05:30")
        ),
        EvalCase(
            "voice-record",
            "record my voice for 10 seconds",
            "voice_recording",
            mapOf("duration_seconds" to "10")
        ),
        EvalCase("blind-aid-off", "turn off blind aid", "deactivate_blind_aid"),
        EvalCase("hindi-whatsapp-open", "व्हाट्सऐप खोलो", "open_app", mapOf("app_name" to "whatsapp")),
        EvalCase("hindi-screen-read", "स्क्रीन पर क्या है", "read_screen")
    )
}
