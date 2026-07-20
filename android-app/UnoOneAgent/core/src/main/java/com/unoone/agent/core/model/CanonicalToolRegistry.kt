package com.unoone.agent.core.model

/**
 * Parameter type for a tool argument, mirroring the LiteRT-LM `@ToolParam` allowed types
 * (String, Int, Boolean, Float, Double, or a List of these). Kept dependency-free in `:core` so
 * the canonical schema is visible to the brain (validation), safety, and test layers without any
 * of them depending on LiteRT-LM.
 */
enum class ToolParamType { STRING, INT, BOOLEAN, FLOAT, DOUBLE, STRING_LIST }

/**
 * One argument of a tool: its name, type, and whether it is required. A parameter is optional when
 * the UnoOne `@Tool` declaration gives it a nullable type or a default value.
 */
data class ToolParamSchema(
    val name: String,
    val type: ToolParamType,
    val required: Boolean
)

/**
 * The full schema for one UnoOne tool: its name plus its ordered argument schemas. This is the
 * single canonical description the brain validates a model-proposed tool call against before the
 * call is ever handed to [com.unoone.agent.execution.ActionExecutor] or
 * [com.unoone.agent.safety.SafetyGuard].
 *
 * Tool names are **never renamed** here: they match the `@Tool` method names in
 * [com.unoone.agent.localbrain.UnoOneToolSet], the `when` branches in `ActionExecutor`, the
 * `riskRules` keys in `SafetyGuard`, and the entries in `ToolPermissionRegistry`. Renaming would
 * break trained tool-call datasets and the existing tests.
 */
data class ToolSchema(
    val name: String,
    val params: List<ToolParamSchema>
) {
    fun param(name: String): ToolParamSchema? = params.firstOrNull { it.name == name }
    val requiredParams: List<ToolParamSchema> get() = params.filter { it.required }
}

/**
 * The one authoritative list of UnoOne tools. The brain ([com.unoone.agent.localbrain.GemmaPlanner])
 * rejects any tool call whose name is not in [names] and validates that every required argument is
 * present and type-compatible before forwarding the call. Tests enforce that `SafetyGuard`,
 * `UnoOneToolSet`, `ActionExecutor`, and `ToolPermissionRegistry` all agree with this list.
 */
object CanonicalToolRegistry {

    val create_note = ToolSchema("create_note", listOf(
        ToolParamSchema("title", ToolParamType.STRING, required = true),
        ToolParamSchema("content", ToolParamType.STRING, required = true),
        ToolParamSchema("tags", ToolParamType.STRING, required = false)
    ))
    val search_notes = ToolSchema("search_notes", listOf(ToolParamSchema("query", ToolParamType.STRING, true)))
    val summarize_text = ToolSchema("summarize_text", listOf(ToolParamSchema("text", ToolParamType.STRING, true)))
    val speak_response = ToolSchema("speak_response", listOf(ToolParamSchema("text", ToolParamType.STRING, true)))
    val voice_recording = ToolSchema("voice_recording", listOf(
        ToolParamSchema("duration_seconds", ToolParamType.INT, required = false),
        ToolParamSchema("title", ToolParamType.STRING, required = false)
    ))
    val web_search = ToolSchema("web_search", listOf(ToolParamSchema("query", ToolParamType.STRING, true)))
    val open_chrome = ToolSchema("open_chrome", emptyList())
    val open_app = ToolSchema("open_app", listOf(
        ToolParamSchema("app_name", ToolParamType.STRING, required = true),
        ToolParamSchema("package_name", ToolParamType.STRING, required = false)
    ))
    val open_url = ToolSchema("open_url", listOf(ToolParamSchema("url", ToolParamType.STRING, true)))
    val open_camera = ToolSchema("open_camera", emptyList())
    val system_control = ToolSchema("system_control", listOf(
        ToolParamSchema("action", ToolParamType.STRING, required = true),
        ToolParamSchema("target", ToolParamType.STRING, required = false),
        ToolParamSchema("value", ToolParamType.STRING, required = false)
    ))
    val read_screen = ToolSchema("read_screen", emptyList())
    val ocr_screen = ToolSchema("ocr_screen", emptyList())
    val create_skill = ToolSchema("create_skill", listOf(
        ToolParamSchema("name", ToolParamType.STRING, required = true),
        ToolParamSchema("steps", ToolParamType.STRING_LIST, required = true)
    ))
    val draft_email = ToolSchema("draft_email", listOf(
        ToolParamSchema("to", ToolParamType.STRING, required = true),
        ToolParamSchema("subject", ToolParamType.STRING, required = true),
        ToolParamSchema("body", ToolParamType.STRING, required = true)
    ))
    val send_whatsapp = ToolSchema("send_whatsapp", listOf(
        ToolParamSchema("number", ToolParamType.STRING, required = true),
        ToolParamSchema("message", ToolParamType.STRING, required = true)
    ))
    val check_calendar = ToolSchema("check_calendar", emptyList())
    val open_calendar = ToolSchema("open_calendar", emptyList())
    val open_calendar_insert = ToolSchema("open_calendar_insert", listOf(
        ToolParamSchema("title", ToolParamType.STRING, required = true),
        ToolParamSchema("start_time", ToolParamType.STRING, required = false),
        ToolParamSchema("end_time", ToolParamType.STRING, required = false)
    ))
    val open_dialer = ToolSchema("open_dialer", listOf(ToolParamSchema("number", ToolParamType.STRING, required = false)))
    val share_text = ToolSchema("share_text", listOf(ToolParamSchema("text", ToolParamType.STRING, true)))
    val delete_notes = ToolSchema("delete_notes", listOf(ToolParamSchema("query", ToolParamType.STRING, true)))
    val delete_all_notes = ToolSchema("delete_all_notes", emptyList())
    val export_data = ToolSchema("export_data", emptyList())
    val detect_objects = ToolSchema("detect_objects", emptyList())
    val deactivate_blind_aid = ToolSchema("deactivate_blind_aid", emptyList())
    /**
     * Describe the current screen as a short scene: foreground app + OCR text (always available with
     * the shipped text-only models), optionally augmented by Gemma multimodal vision when a
     * vision-capable `.litertlm` artifact is loaded (device-time, inactive until then). Capturing +
     * analyzing the screen can read sensitive content, so the safety tier is STRONG_CONFIRM.
     */
    val describe_scene = ToolSchema("describe_scene", listOf(
        ToolParamSchema("aspect", ToolParamType.STRING, required = false)
    ))
    /**
     * Drive the UnoOne Secure Browser (Page Agent on a hardened WebView) and run a task.
     * Standard mode resolves and gates the target to an approved origin. Explicit Prototype/Off
     * admits arbitrary public HTTPS targets. Transport restrictions and the session-bound bridge
     * remain enforced. Risk tier CONFIRM in Standard: it drives a browser.
     */
    val secure_browser_task = ToolSchema("secure_browser_task", listOf(
        ToolParamSchema("origin", ToolParamType.STRING, required = true),
        ToolParamSchema("task", ToolParamType.STRING, required = true)
    ))
    /** Opens the offline Document Agent picker for a fillable PDF or DOCX template. */
    val prepare_document_fill = ToolSchema("prepare_document_fill", listOf(
        ToolParamSchema("format", ToolParamType.STRING, required = true)
    ))

    /** All canonical tools, in declaration order. */
    val tools: List<ToolSchema> = listOf(
        create_note, search_notes, summarize_text, speak_response, voice_recording, web_search,
        open_chrome, open_app, open_url, open_camera, system_control, read_screen, ocr_screen,
        create_skill, draft_email, send_whatsapp, check_calendar, open_calendar, open_calendar_insert,
        open_dialer, share_text, delete_notes, delete_all_notes, export_data, detect_objects,
        deactivate_blind_aid, describe_scene, secure_browser_task, prepare_document_fill
    )

    /** The set of tool names a model is allowed to propose. Anything else is rejected by the brain. */
    val names: Set<String> = tools.map { it.name }.toSet()

    /** Schema for a tool name, or null if the name is not a canonical UnoOne tool. */
    fun schemaFor(name: String): ToolSchema? = tools.firstOrNull { it.name == name }

    /** True iff [name] is a canonical UnoOne tool the model may propose. */
    fun isKnown(name: String): Boolean = name in names
}
