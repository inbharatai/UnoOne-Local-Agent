package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Declares every capability UnoOne exposes to Gemma 4 E4B via LiteRT-LM manual tool calling.
 *
 * The function bodies are stubs: with [automaticToolCalling = false] the model only uses
 * these signatures to propose a tool call. Real execution always routes through the canonical
 * schema, permission checks, [com.unoone.agent.safety.SafetyGuard], user confirmation where needed,
 * [com.unoone.agent.execution.ActionExecutor], and post-execution verification.
 */
class UnoOneToolSet : ToolSet {

    @Tool(description = "Create a local note only when both a usable title and note content are available")
    fun create_note(
        @ToolParam(description = "Short title for the note; do not invent one when the user gave a specific title") title: String,
        @ToolParam(description = "Full note content copied from the user's request") content: String,
        @ToolParam(description = "Optional comma-separated tags explicitly requested or clearly derived from the note topic") tags: String? = null
    ): String = "Note '$title' proposed."

    @Tool(description = "Search saved local notes by a user-supplied keyword or phrase")
    fun search_notes(
        @ToolParam(description = "Search query copied from the user's request") query: String
    ): String = "Searching notes for '$query'."

    @Tool(description = "Summarize a supplied block of text; never fabricate text that is not present")
    fun summarize_text(
        @ToolParam(description = "Text to summarize") text: String
    ): String = "Summary proposed."

    @Tool(description = "Speak a concise response or ask one clarification question; this tool performs no phone action")
    fun speak_response(
        @ToolParam(description = "Short user-facing text in the active response language") text: String
    ): String = text

    @Tool(description = "Record a short voice memo, transcribe it offline, and save it as a local note")
    fun voice_recording(
        @ToolParam(description = "Maximum recording duration in seconds from 1 to 30; omit for the safe default") duration_seconds: Int? = null,
        @ToolParam(description = "Optional note title; omit when the user did not provide one") title: String? = null
    ): String = "Voice memo proposed."

    @Tool(description = "Search the web for an answer when online; never use this for opening an app or URL")
    fun web_search(
        @ToolParam(description = "Search query") query: String
    ): String = "Searching the web for '$query'."

    @Tool(description = "Open the Chrome browser only; use open_app for another installed application")
    fun open_chrome(): String = "Chrome open proposed."

    @Tool(description = "Open an installed application by friendly name. Package resolution and foreground verification are performed by native Android code")
    fun open_app(
        @ToolParam(description = "Human-readable installed app name, for example WhatsApp or Gmail") app_name: String,
        @ToolParam(description = "Exact Android package only when it is present in verified context; otherwise omit") package_name: String? = null
    ): String = "Opening $app_name proposed."

    @Tool(description = "Open a fully specified web URL after native safety sanitization")
    fun open_url(
        @ToolParam(description = "Complete http or https URL explicitly supplied by the user or trusted context") url: String
    ): String = "Opening URL proposed."

    @Tool(description = "Launch the device camera app; this is not Blind Aid")
    fun open_camera(): String = "Camera open proposed."

    @Tool(description = "Control the visible Android UI through AccessibilityService using only one allowed action")
    fun system_control(
        @ToolParam(description = "Allowed action: click, type, fill, scroll_up, scroll_down, swipe, go_back, go_home, open_notifications, open_recents, find_and_click, long_press, read_screen") action: String,
        @ToolParam(description = "Visible target text or direction when the action requires one; never invent a target") target: String? = null,
        @ToolParam(description = "Text value for fill or type actions, copied exactly from the user's request") value: String? = null
    ): String = "Accessibility action proposed."

    @Tool(description = "Read visible accessibility text from the current screen")
    fun read_screen(): String = "Screen read proposed."

    @Tool(description = "Capture the current screen and run OCR only when accessibility text is unavailable or insufficient")
    fun ocr_screen(): String = "Screen OCR proposed."

    @Tool(description = "Create a reusable multi-step skill from explicit user-provided steps")
    fun create_skill(
        @ToolParam(description = "Skill name") name: String,
        @ToolParam(description = "Ordered list of explicit step descriptions") steps: List<String>
    ): String = "Skill '$name' proposed."

    @Tool(description = "Prepare a reviewable email draft. This never presses Send and requires a real recipient address")
    fun draft_email(
        @ToolParam(description = "Verified recipient email address; ask the user when missing") to: String,
        @ToolParam(description = "Email subject; ask or derive conservatively from the supplied body") subject: String,
        @ToolParam(description = "Email body copied from the user's request") body: String
    ): String = "Email draft proposed for $to."

    @Tool(description = "Open WhatsApp with a reviewable pre-filled message. Despite the legacy function name, this never presses Send")
    fun send_whatsapp(
        @ToolParam(description = "Verified phone number when supplied; use an empty value only to open WhatsApp's recipient picker") number: String,
        @ToolParam(description = "Message text copied exactly from the user's request") message: String
    ): String = "WhatsApp draft proposed."

    @Tool(description = "Read today's calendar events; do not use this to open the Calendar app")
    fun check_calendar(): String = "Calendar read proposed."

    @Tool(description = "Open the device's default calendar app without creating an event")
    fun open_calendar(): String = "Calendar open proposed."

    @Tool(description = "Open a reviewable calendar event form only when the user explicitly asks to add, create, schedule or set a reminder")
    fun open_calendar_insert(
        @ToolParam(description = "Event title grounded in the user's request") title: String,
        @ToolParam(description = "Optional verified ISO-8601 start time; omit when deterministic parsing did not resolve it") start_time: String? = null,
        @ToolParam(description = "Optional verified ISO-8601 end time; omit when deterministic parsing did not resolve it") end_time: String? = null
    ): String = "Calendar event draft proposed for '$title'."

    @Tool(description = "Open the phone dialer with an optional verified number; this never places the call automatically")
    fun open_dialer(
        @ToolParam(description = "Optional verified phone number") number: String? = null
    ): String = "Dialer open proposed."

    @Tool(description = "Open the system share sheet with user-supplied text; this does not choose a recipient or complete sharing")
    fun share_text(
        @ToolParam(description = "Text to share") text: String
    ): String = "Share proposed."

    @Tool(description = "Delete notes matching a specific query; requires native confirmation before execution")
    fun delete_notes(
        @ToolParam(description = "Query that identifies notes for deletion") query: String
    ): String = "Delete notes proposed for '$query'."

    @Tool(description = "Delete all saved notes; destructive and always requires strong native confirmation")
    fun delete_all_notes(): String = "Delete all notes proposed."

    @Tool(description = "Export local user data through the guarded export workflow")
    fun export_data(): String = "Export proposed."

    @Tool(description = "Activate Blind Aid camera guidance and offline obstacle detection")
    fun detect_objects(): String = "Blind Aid activation proposed."

    @Tool(description = "Deactivate Blind Aid camera guidance and obstacle detection")
    fun deactivate_blind_aid(): String = "Blind Aid deactivation proposed."

    @Tool(description = "Describe the current screen from foreground-app, accessibility and OCR evidence; screen capture is sensitive and confirmation-gated")
    fun describe_scene(
        @ToolParam(description = "Optional aspect to focus on, such as buttons or total; never request passwords or OTPs") aspect: String? = null
    ): String = "Scene description proposed."

    @Tool(description = "Open UnoOne Secure Browser and run one bounded Page Agent task. Native browser policy independently authorizes every DOM action")
    fun secure_browser_task(
        @ToolParam(description = "Approved friendly origin, bare host, or full public HTTPS URL allowed by the active security mode") origin: String,
        @ToolParam(description = "Exact browser task, including any instruction to stop before final submission") task: String
    ): String = "Secure Browser task proposed for $origin."

    @Tool(description = "Open the fully offline Document Agent for a fillable PDF AcroForm or DOCX template and save a new copy")
    fun prepare_document_fill(
        @ToolParam(description = "Document format: pdf or docx") format: String
    ): String = "Offline document fill proposed for $format."
}
