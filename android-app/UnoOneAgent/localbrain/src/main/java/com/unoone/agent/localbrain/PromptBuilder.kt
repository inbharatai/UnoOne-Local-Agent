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
        appendLine("Reply in the same language as the user's current command, unless the user explicitly asks for another language. Do not infer the response language only from the selected TTS voice or from previous turns.")
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
            if (context.voiceLanguage.isNotBlank()) {
                appendLine("- user language: ${sanitizeContext(context.voiceLanguage)}")
            }
        }
    }

    fun buildChatPrompt(command: String): String =
        "You are UnoOne, a helpful local AI assistant. User said: ${sanitizeContext(command)}. Respond briefly."

    /**
     * System instruction for the dedicated CHAT conversation — a tool-less, conversational brain
     * carved out of the same loaded engine (own KV-cache). It is explicitly NOT a phone-action
     * planner: it answers questions, and if asked to do something on the phone it declines and asks
     * the user to phrase it as a command (so the action still reaches the safety-gated agent path).
     */
    fun buildChatSystemInstruction(): String = buildString {
        appendLine("You are UnoOne, a helpful, privacy-first offline AI assistant that converses with the user.")
        appendLine("Answer conversationally and briefly — UnoOne reads your reply aloud, so keep it short and clear.")
        appendLine("You have no tools here and are not planning phone actions. If the user asks you to DO something on the phone (open an app, create or read a note, read the screen, send a message, make a call), tell them you cannot do that in chat and ask them to phrase it as a command.")
        appendLine("Never reveal passwords, OTPs, card data, banking credentials or any secret.")
    }

    /**
     * Per-turn user message for the CHAT lane. Carries the response-language directive up front so
     * the model answers in the user's current language and does not switch based on the TTS voice
     * or earlier turns (the "English question → Hindi answer" regression). The command is sanitized
     * like any untrusted context.
     */
    fun buildChatUserMessage(command: String): String = buildString {
        appendLine("Reply in the same language as the user's current message, unless they explicitly ask for a different language. Do not infer the reply language from the voice/TTS setting or from earlier turns.")
        append("User: ")
        append(sanitizeContext(command))
    }

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
