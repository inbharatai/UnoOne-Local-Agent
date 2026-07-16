package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Declares every capability UnoOne exposes to Gemma 4 E2B via LiteRT-LM manual tool calling.
 *
 * The function bodies are stubs: with [automaticToolCalling = false] the model only uses
 * these signatures to generate tool-call JSON. Real execution always routes through
 * [com.unoone.agent.safety.SafetyGuard] and [com.unoone.agent.execution.ActionExecutor].
 */
class UnoOneToolSet : ToolSet {

    @Tool(description = "Create a local note")
    fun create_note(
        @ToolParam(description = "Short title for the note") title: String,
        @ToolParam(description = "Full note content") content: String,
        @ToolParam(description = "Optional comma-separated tags") tags: String? = null
    ): String = "Note '$title' saved."

    @Tool(description = "Search saved notes by keyword")
    fun search_notes(
        @ToolParam(description = "Search query") query: String
    ): String = "Searching notes for '$query'."

    @Tool(description = "Summarize a block of text")
    fun summarize_text(
        @ToolParam(description = "Text to summarize") text: String
    ): String = "Summary ready."

    @Tool(description = "Speak a response to the user through offline TTS")
    fun speak_response(
        @ToolParam(description = "Text to speak") text: String
    ): String = text

    @Tool(description = "Record a short voice memo, transcribe it offline, and save it as a note")
    fun voice_recording(
        @ToolParam(description = "Max recording duration in seconds (1-30, default 5)") duration_seconds: Int? = null,
        @ToolParam(description = "Optional note title; defaults to the start of the transcription") title: String? = null
    ): String = "Voice memo recorded and saved."

    @Tool(description = "Search the web for an answer (online only; returns nothing offline). Uses a privacy-respecting scrape, never auto-opens links")
    fun web_search(
        @ToolParam(description = "Search query") query: String
    ): String = "Searching the web for '$query'."

    @Tool(description = "Open the Chrome browser")
    fun open_chrome(): String = "Chrome opened."

    @Tool(description = "Open any installed app by package name or friendly name")
    fun open_app(
        @ToolParam(description = "Human-readable app name, e.g. WhatsApp") app_name: String,
        @ToolParam(description = "Exact Android package name if known") package_name: String? = null
    ): String = "Opening $app_name."

    @Tool(description = "Open a web URL after safety sanitization")
    fun open_url(
        @ToolParam(description = "URL to open") url: String
    ): String = "Opening $url."

    @Tool(description = "Launch the device camera app")
    fun open_camera(): String = "Camera opened."

    @Tool(description = "Control the device UI via AccessibilityService")
    fun system_control(
        @ToolParam(description = "Action: click, type, fill, scroll_up, scroll_down, swipe, go_back, go_home, open_notifications, open_recents, find_and_click, long_press, read_screen") action: String,
        @ToolParam(description = "Target text or direction") target: String? = null,
        @ToolParam(description = "Value for fill/type actions") value: String? = null
    ): String = "Executing $action."

    @Tool(description = "Read visible text from the current screen")
    fun read_screen(): String = "Screen text captured."

    @Tool(description = "Capture a screenshot and run OCR for apps without accessibility labels")
    fun ocr_screen(): String = "OCR performed."

    @Tool(description = "Create a reusable multi-step skill")
    fun create_skill(
        @ToolParam(description = "Skill name") name: String,
        @ToolParam(description = "List of step descriptions") steps: List<String>
    ): String = "Skill '$name' saved."

    @Tool(description = "Draft an email; the user must review and press send")
    fun draft_email(
        @ToolParam(description = "Recipient email address") to: String,
        @ToolParam(description = "Email subject") subject: String,
        @ToolParam(description = "Email body") body: String
    ): String = "Email draft prepared for $to."

    @Tool(description = "Open WhatsApp with a pre-filled message; the user must press send")
    fun send_whatsapp(
        @ToolParam(description = "Phone number with optional country code") number: String,
        @ToolParam(description = "Message text") message: String
    ): String = "WhatsApp draft prepared for $number."

    @Tool(description = "Check today's calendar events")
    fun check_calendar(): String = "Calendar checked."

    @Tool(description = "Open the device's default calendar app")
    fun open_calendar(): String = "Calendar opened."

    @Tool(description = "Open the calendar event creator")
    fun open_calendar_insert(
        @ToolParam(description = "Event title") title: String,
        @ToolParam(description = "Optional start time ISO string") start_time: String? = null,
        @ToolParam(description = "Optional end time ISO string") end_time: String? = null
    ): String = "Calendar insert opened for '$title'."

    @Tool(description = "Open the phone dialer with an optional number")
    fun open_dialer(
        @ToolParam(description = "Optional phone number") number: String? = null
    ): String = "Dialer opened."

    @Tool(description = "Share text through the system share sheet")
    fun share_text(
        @ToolParam(description = "Text to share") text: String
    ): String = "Share sheet opened."

    @Tool(description = "Delete notes matching a query")
    fun delete_notes(
        @ToolParam(description = "Query to match notes for deletion") query: String
    ): String = "Delete request queued for '$query'."

    @Tool(description = "Delete all saved notes")
    fun delete_all_notes(): String = "Delete all notes requested."

    @Tool(description = "Export user data")
    fun export_data(): String = "Export requested."

    @Tool(description = "Activate blind-aid camera and obstacle detection")
    fun detect_objects(): String = "Blind Aid activated."

    @Tool(description = "Deactivate blind-aid camera and obstacle detection")
    fun deactivate_blind_aid(): String = "Blind Aid deactivated."

    @Tool(description = "Describe the current screen as a short scene (foreground app + visible text); optionally narrow what to look for with aspect. Sensitive: captures the screen.")
    fun describe_scene(
        @ToolParam(description = "Optional what to focus on, e.g. 'buttons', 'the total', 'any OTP'") aspect: String? = null
    ): String = "Scene described."

    @Tool(description = "Open the UnoOne Secure Browser and run a PageAgent task. Standard accepts approved origins such as unigurus, uniassist, testsprep and inbharat. Explicit Prototype/Off accepts any public HTTPS URL. Standard keeps confirmations and sensitive-step gates enabled.")
    fun secure_browser_task(
        @ToolParam(description = "Approved origin: friendly name ('unigurus'), bare host ('unigurus.com'), or full HTTPS URL") origin: String,
        @ToolParam(description = "What UnoOne should do on the page, e.g. 'fill the profile form and stop before final submission'") task: String
    ): String = "Opening Secure Browser for $origin."

    @Tool(description = "Open the fully offline Document Agent to fill a PDF AcroForm or DOCX template and save a new copy")
    fun prepare_document_fill(
        @ToolParam(description = "Document format: pdf or docx") format: String
    ): String = "Opening offline document fill for $format."
}
