package com.unoone.agent.localbrain

import com.unoone.agent.core.model.ModelFamily

/**
 * Configurable mobile context budget. UnoOne never sends a model's full context window (Gemma 4 E2B
 * supports 128K, but a phone should not) — instead it sends a small, deterministic slice per
 * command type. Char caps approximate ~4 chars/token, so the budgets below map to the token ranges
 * required by the migration: NORMAL ≈ 2–4K tokens, SCREEN_READING up to ~8K, ADVANCED up to ~16K.
 *
 * The system instruction (the tool schema list) is built separately and is **never** truncated —
 * tool names and JSON are never cut.
 *
 * @property visibleTextChars   Cap on accessibility visible-screen text.
 * @property ocrChars           Cap on OCR text.
 * @property notesJoinChars     Cap on the joined recent-notes line.
 * @property memoryChars        Cap on user-memory text.
 * @property lastResultChars    Cap on the last tool result.
 * @property recentCommandLimit How many recent commands to include.
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
        /** Screen-reading/planning commands expand the budget; everything else stays NORMAL. */
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
 * Prompt assembler for the on-device Gemma brain via LiteRT-LM.
 *
 * Model-family aware:
 * - [buildSystemInstruction] (no-arg) preserves the **exact** legacy Gemma 3n E4B instruction so
 *   existing behaviour and tests are unchanged.
 * - [buildSystemInstruction] with [ModelFamily.GEMMA_4] returns an enhanced, tool-call-tuned
 *   instruction that explicitly states the model proposes (never executes), prefers one precise
 *   tool call, does not fabricate apps/contacts/permissions/screen elements/tool results, asks for
 *   clarification through `speak_response` when genuinely ambiguous, uses visible screen text and
 *   local context only when provided, and keeps user-facing responses short (they are spoken aloud).
 *
 * Untrusted context (visible text, OCR, notes, memory, last tool result) is sanitized of Gemma
 * control tokens and tool-call injection sequences before it enters the prompt, then truncated to
 * the active [ContextBudget].
 */
object PromptBuilder {

    // Retained for any external reader; the authoritative caps now live in ContextBudget.
    private const val VISIBLE_TEXT_LIMIT = 2_000
    private const val OCR_TEXT_LIMIT = 1_000

    /** Legacy Gemma 3n E4B system instruction — unchanged byte-for-byte. */
    private val gemma3nInstruction: String = buildString {
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

    /** Enhanced Gemma 4 E2B system instruction — all 26 tools + explicit planning rules. */
    private val gemma4Instruction: String = buildString {
        appendLine("You are UnoOne, a fully offline Android AI agent that plans phone actions.")
        appendLine("You PROPOSE actions using the tools below; you never execute them. The app executes every tool call after safety checks and user confirmation.")
        appendLine("Pick the single best tool for the user's request. Prefer exactly one precise tool call per response; never chain multiple tool calls in one response.")
        appendLine("For messages or payments, only DRAFT them; never send or pay silently. Use draft_email or send_whatsapp (both open a draft the user reviews and presses send).")
        appendLine("Never fabricate apps, contacts, permissions, screen elements, or tool results. Only use apps, contacts, and screen text that appear in the provided context.")
        appendLine("If the command is genuinely ambiguous, do not guess — call speak_response to ask the user a short clarifying question.")
        appendLine("Use visible screen text and the local context only when it is provided in this prompt; do not assume what is on screen.")
        appendLine("Keep any spoken response short, because UnoOne speaks it aloud to the user.")
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
        appendLine("- open_calendar_insert(title, start_time?, end_time?)")
        appendLine("- open_dialer(number?)")
        appendLine("- share_text(text)")
        appendLine("- delete_notes(query)")
        appendLine("- delete_all_notes()")
        appendLine("- export_data()")
        appendLine("- detect_objects()")
        appendLine("- deactivate_blind_aid()")
        appendLine("- describe_scene(aspect?)  // describe the current screen as a short scene; aspect optionally narrows what to look for")
    }

    /** Legacy no-arg instruction (Gemma 3n E4B). Preserved exactly for back-compat and tests. */
    fun buildSystemInstruction(): String = buildSystemInstruction(ModelFamily.GEMMA_3N)

    /** Family-aware system instruction. Both families use LiteRT-LM native systemInstruction. */
    fun buildSystemInstruction(family: ModelFamily): String = when (family) {
        ModelFamily.GEMMA_3N -> gemma3nInstruction
        ModelFamily.GEMMA_4 -> gemma4Instruction
    }

    /** Legacy budget: defaults to NORMAL. */
    fun buildUserMessage(command: String, context: ContextSnapshot): String =
        buildUserMessage(command, context, ContextBudget.NORMAL)

    /**
     * Builds the per-turn user message, sanitizing untrusted context of control/injection tokens
     * and truncating each field to the active [ContextBudget]. The tool schema (system instruction)
     * is never touched here.
     */
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

    /**
     * Chat-only prompt (no tool constraints) for simple Q&A fallback.
     */
    fun buildChatPrompt(command: String): String =
        "You are UnoOne, a helpful local AI assistant. User said: ${sanitizeContext(command)}. Respond briefly."

    /**
     * Strips Gemma control tokens and tool-call injection sequences from untrusted text (screen
     * text, OCR, notes, memory, downloaded content) so a hostile screen or web page cannot hijack
     * the model by injecting turn boundaries or a fake tool call. Deterministic and side-effect-free
     * so it is unit-testable.
     */
    fun sanitizeContext(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for (token in CONTROL_TOKENS) {
            out = out.replace(token, "")
        }
        // Collapse the empty slots left behind by removed tokens so words don't run together.
        return out.replace("  ", " ").trim()
    }

    /** Gemma-family control tokens + tool-call injection literals removed from untrusted context. */
    private val CONTROL_TOKENS: List<String> = listOf(
        "<start_of_turn>", "</start_of_turn>", "<end_of_turn>",
        "<bos>", "<eos>", "<pad>",
        "<start_of_image>", "</start_of_image>", "<end_of_image>",
        "<tool>", "</tool>",
        "\"tool_calls\"", "\"tool\":", "\"function_call\""
    )
}