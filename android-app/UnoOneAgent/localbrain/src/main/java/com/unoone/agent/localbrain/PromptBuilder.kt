package com.unoone.agent.localbrain

import com.unoone.agent.core.model.ModelFamily

/**
 * Configurable mobile context budget. UnoOne never sends the full theoretical context window; it
 * sends a deterministic slice appropriate to the current command. Character caps approximate four
 * characters per token. The system instruction and canonical tool names are never truncated.
 */
enum class ContextBudget(
    val label: String,
    val visibleTextChars: Int,
    val ocrChars: Int,
    val notesJoinChars: Int,
    val memoryChars: Int,
    val lastResultChars: Int,
    val recentCommandLimit: Int
) {
    NORMAL("Normal", 2_000, 1_000, 600, 600, 500, 3),
    SCREEN_READING("Screen reading", 4_000, 8_000, 600, 600, 500, 3),
    ADVANCED("Advanced", 8_000, 8_000, 1_000, 1_000, 1_000, 5);

    companion object {
        fun forCommand(command: String): ContextBudget {
            val lowered = command.lowercase()
            return if (KEYWORDS.any { lowered.contains(it) }) SCREEN_READING else NORMAL
        }

        private val KEYWORDS = listOf(
            "read screen", "what's on screen", "what is on screen", "on my screen",
            "ocr", "read the screen", "screen text", "describe screen"
        )
    }
}

/**
 * Prompt assembler for UnoOne's Gemma 4 E2B brain through LiteRT-LM.
 *
 * Untrusted context such as visible text, OCR, notes, memory and tool results is stripped of model
 * control tokens and tool-call injection literals before entering the prompt, then truncated to the
 * active [ContextBudget].
 */
object PromptBuilder {

    private val gemma4Instruction: String = buildString {
        appendLine("You are UnoOne, a privacy-first offline Android AI agent that plans phone actions.")
        appendLine("You only PROPOSE actions using the tools below. The app validates permissions, safety and confirmation before executing anything.")
        appendLine("Pick exactly one best tool per response. Multi-step work is controlled by the app's bounded agent loop, not by emitting multiple tool calls.")
        appendLine("Never fabricate apps, contacts, permissions, screen elements, page content or tool results. Use only facts supplied in the current context.")
        appendLine("Never enter or expose passwords, OTPs, card data, banking credentials or authentication secrets.")
        appendLine("Never send a message or make a payment silently. Never install an app, bypass CAPTCHA or accept legal declarations.")
        appendLine("Email and WhatsApp tools only prepare drafts that the user must review and send.")
        appendLine("If the request is genuinely ambiguous, use speak_response to ask one short clarifying question.")
        appendLine("Keep spoken responses concise because UnoOne reads them aloud.")
        appendLine()
        appendLine("Available tools:")
        appendLine("- create_note(title, content, tags?)")
        appendLine("- search_notes(query)")
        appendLine("- summarize_text(text)")
        appendLine("- speak_response(text)")
        appendLine("- voice_recording(duration_seconds?, title?)")
        appendLine("- web_search(query)")
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
        appendLine("- open_calendar()")
        appendLine("- open_calendar_insert(title, start_time?, end_time?)")
        appendLine("- open_dialer(number?)")
        appendLine("- share_text(text)")
        appendLine("- delete_notes(query)")
        appendLine("- delete_all_notes()")
        appendLine("- export_data()")
        appendLine("- detect_objects()")
        appendLine("- deactivate_blind_aid()")
        appendLine("- describe_scene(aspect?)")
    }

    /** Compatibility overload used by existing callers and tests. */
    fun buildSystemInstruction(): String = gemma4Instruction

    /** UnoOne V2 accepts only [ModelFamily.GEMMA_4]. */
    fun buildSystemInstruction(family: ModelFamily): String = when (family) {
        ModelFamily.GEMMA_4 -> gemma4Instruction
    }

    fun buildUserMessage(command: String, context: ContextSnapshot): String =
        buildUserMessage(command, context, ContextBudget.NORMAL)

    fun buildUserMessage(command: String, context: ContextSnapshot, budget: ContextBudget): String = buildString {
        appendLine("User command: ${sanitizeContext(command)}")
        if (!context.isEmpty()) {
            appendLine()
            appendLine("Current context:")
            if (context.currentPackage.isNotBlank()) {
                appendLine("- current app: ${sanitizeContext(context.currentPackage)}")
            }
            if (context.currentActivity.isNotBlank()) {
                appendLine("- current activity: ${sanitizeContext(context.currentActivity)}")
            }
            if (context.visibleText.isNotBlank()) {
                appendLine("- visible screen text: ${sanitizeContext(context.visibleText).take(budget.visibleTextChars)}")
            }
            if (context.ocrText.isNotBlank()) {
                appendLine("- OCR text: ${sanitizeContext(context.ocrText).take(budget.ocrChars)}")
            }
            if (context.recentNotes.isNotEmpty()) {
                val joined = sanitizeContext(context.recentNotes.joinToString(", "))
                appendLine("- recent notes: ${joined.take(budget.notesJoinChars)}")
            }
            if (context.userMemory.isNotBlank()) {
                appendLine("- user memory: ${sanitizeContext(context.userMemory).take(budget.memoryChars)}")
            }
            if (context.activeSkills.isNotEmpty()) {
                appendLine("- active skills: ${sanitizeContext(context.activeSkills.joinToString(", "))}")
            }
            val recent = context.recentCommands.takeLast(budget.recentCommandLimit)
            if (recent.isNotEmpty()) {
                appendLine("- recent commands: ${sanitizeContext(recent.joinToString(" → "))}")
            }
            if (context.lastToolResult.isNotBlank()) {
                appendLine("- last tool result: ${sanitizeContext(context.lastToolResult).take(budget.lastResultChars)}")
            }
        }
    }

    fun buildChatPrompt(command: String): String =
        "You are UnoOne, a helpful local AI assistant. User said: ${sanitizeContext(command)}. Respond briefly."

    fun sanitizeContext(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for (token in CONTROL_TOKENS) out = out.replace(token, "")
        return out.replace("  ", " ").trim()
    }

    private val CONTROL_TOKENS: List<String> = listOf(
        "<start_of_turn>", "</start_of_turn>", "<end_of_turn>",
        "<bos>", "<eos>", "<pad>",
        "<start_of_image>", "</start_of_image>", "<end_of_image>",
        "<tool>", "</tool>",
        "\"tool_calls\"", "\"tool\":", "\"function_call\""
    )
}
