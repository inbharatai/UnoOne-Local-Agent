package com.unoone.agent.localbrain

/**
 * Prompt assembler for Gemma 4 via LiteRT-LM.
 *
 * Provides an offline-first persona and a concise context block so the model can
 * choose the right UnoOne tool without leaking full Android objects into the prompt.
 */
object PromptBuilder {

    private const val VISIBLE_TEXT_LIMIT = 2_000
    private const val OCR_TEXT_LIMIT = 1_000

    fun buildSystemInstruction(): String = buildString {
        appendLine("You are UnoOne, a fully offline Android AI agent.")
        appendLine("You control the user's phone via the tools listed below.")
        appendLine("ALWAYS pick the single best tool for the user's request.")
        appendLine("NEVER chain multiple tool calls in one response.")
        appendLine("For messages or payments, only DRAFT them; never send or pay silently.")
        appendLine()
        appendLine("Available tools:")
        appendLine("- create_note(title, content, tags?)")
        appendLine("- search_notes(query)")
        appendLine("- summarize_text(text)")
        appendLine("- speak_response(text)")
        appendLine("- open_chrome()")
        appendLine("- open_app(app_name, package_name?)")
        appendLine("- open_url(url)")
        appendLine("- open_camera()")
        appendLine("- system_control(action, target?, value?)")
        appendLine("- read_screen()")
        appendLine("- ocr_screen()")
        appendLine("- create_skill(name, steps)")
        appendLine("- draft_email(to, subject, body)")
        appendLine("- send_whatsapp(number, message)")
        appendLine("- check_calendar()")
        appendLine("- open_calendar_insert(title, start_time?, end_time?)")
        appendLine("- open_dialer(number?)")
        appendLine("- share_text(text)")
        appendLine("- delete_notes(query)")
        appendLine("- delete_all_notes()")
        appendLine("- export_data()")
        appendLine("- detect_objects()")
        appendLine("- deactivate_blind_aid()")
    }

    fun buildUserMessage(command: String, context: ContextSnapshot): String = buildString {
        appendLine("User command: $command")
        if (!context.isEmpty()) {
            appendLine()
            appendLine("Current context:")
            if (context.currentPackage.isNotBlank()) {
                appendLine("- current app: ${context.currentPackage}")
            }
            if (context.currentActivity.isNotBlank()) {
                appendLine("- current activity: ${context.currentActivity}")
            }
            if (context.visibleText.isNotBlank()) {
                appendLine("- visible screen text: ${context.visibleText.take(VISIBLE_TEXT_LIMIT)}")
            }
            if (context.ocrText.isNotBlank()) {
                appendLine("- OCR text: ${context.ocrText.take(OCR_TEXT_LIMIT)}")
            }
            if (context.recentNotes.isNotEmpty()) {
                appendLine("- recent notes: ${context.recentNotes.joinToString(", ")}")
            }
            if (context.userMemory.isNotBlank()) {
                appendLine("- user memory: ${context.userMemory}")
            }
            if (context.activeSkills.isNotEmpty()) {
                appendLine("- active skills: ${context.activeSkills.joinToString(", ")}")
            }
            if (context.recentCommands.isNotEmpty()) {
                appendLine("- recent commands: ${context.recentCommands.joinToString(" → ")}")
            }
            if (context.lastToolResult.isNotBlank()) {
                appendLine("- last tool result: ${context.lastToolResult.take(500)}")
            }
        }
    }

    /**
     * Chat-only prompt (no tool constraints) for simple Q&A fallback.
     */
    fun buildChatPrompt(command: String): String =
        "You are UnoOne, a helpful local AI assistant. User said: $command. Respond briefly."
}
