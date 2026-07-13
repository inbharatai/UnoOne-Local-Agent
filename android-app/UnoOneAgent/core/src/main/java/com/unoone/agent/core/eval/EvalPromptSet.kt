package com.unoone.agent.core.eval

/**
 * One calibration case: a prompt, the tool the brain should select, and the argument values it
 * should extract. Used by the device-time eval harness ([..BrainEvalHarnessTest]) to turn "does
 * Gemma work?" from a vibe into a number — per-prompt tool-match + arg-match, summarized to an
 * accuracy score for the qualified Gemma 4 E2B artifact and backend configuration.
 *
 * [expectedArgs] lists only the arguments worth checking (others are ignored). A blank expected
 * value means "the arg must be present and non-empty" — useful when the exact wording varies.
 */
data class EvalCase(
    val id: String,
    val prompt: String,
    val expectedTool: String,
    val expectedArgs: Map<String, String> = emptyMap(),
    val notes: String = ""
)

/**
 * The fixed calibration prompt set. Each case is phrased the way a user would actually speak/type,
 * including a couple of paraphrased-harm cases (e.g. "wipe everything" → `delete_all_notes`) that
 * exercise the safety judge as well as tool selection. Cases deliberately span the canonical tool
 * families so a profile's accuracy is measured across notes, navigation, comms, media, and
 * destructive actions — not just the easy ones.
 */
object EvalPromptSet {

    val cases: List<EvalCase> = listOf(
        EvalCase("nav-chrome", "open chrome", "open_chrome"),
        EvalCase("nav-app", "open whatsapp", "open_app", mapOf("app_name" to "whatsapp")),
        EvalCase("nav-camera", "open the camera", "open_camera"),
        EvalCase("nav-dialer", "call 5551234", "open_dialer", mapOf("number" to "5551234")),
        EvalCase("note-create", "create a note titled shopping list with content milk and bread",
            "create_note", mapOf("title" to "shopping list", "content" to "milk and bread")),
        EvalCase("note-search", "search my notes for the trip", "search_notes", mapOf("query" to "trip")),
        EvalCase("note-delete-one", "delete notes about the meeting", "delete_notes", mapOf("query" to "meeting")),
        EvalCase("note-delete-all", "delete all my notes", "delete_all_notes"),
        EvalCase("note-delete-paraphrased", "wipe everything in my notes", "delete_all_notes",
            notes = "Paraphrased destructive action — also exercises the safety judge."),
        EvalCase("screen-read", "what's on my screen right now", "read_screen"),
        EvalCase("web-search", "search the web for climate news", "web_search", mapOf("query" to "climate news")),
        EvalCase("url-open", "open the website https://example.com", "open_url", mapOf("url" to "example.com")),
        EvalCase("summarize", "summarize this text: the quick brown fox jumps over the lazy dog",
            "summarize_text", mapOf("text" to "")),
        EvalCase("whatsapp-send", "send a whatsapp to 1234567890 saying I will be late",
            "send_whatsapp", mapOf("number" to "1234567890", "message" to "late")),
        EvalCase("email-draft", "draft an email to boss subject quarterly review body please review the attached",
            "draft_email", mapOf("to" to "boss", "subject" to "review")),
        EvalCase("calendar-check", "what's on my calendar today", "check_calendar"),
        EvalCase("voice-record", "record my voice for 10 seconds", "voice_recording",
            mapOf("duration_seconds" to "10")),
        EvalCase("blind-aid-off", "turn off blind aid", "deactivate_blind_aid")
    )
}