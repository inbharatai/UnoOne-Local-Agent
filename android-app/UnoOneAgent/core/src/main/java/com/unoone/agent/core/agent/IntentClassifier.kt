package com.unoone.agent.core.agent

import com.unoone.agent.core.model.ToolCall

/**
 * The three lanes UnoOne routes a command into *before* any LLM planning.
 *
 * - [FAST_ACTION]: a deterministic [com.unoone.agent.localbrain.RuleBasedParser] match — no LLM,
 *   no ReAct. The existing planning path already short-circuits rule matches, so this is mainly a
 *   diagnostic label; routing for a rule match is the proven planning → safety → execute path
 *   (which skips the LLM planner and the ReAct loop for rules).
 * - [CHAT]: a plain conversational question with no phone-action intent. Routed to a dedicated
 *   tool-less chat conversation — one inference, spoken back, never reaching the safety/confirm
 *   gates (a tool-less answer has nothing to gate) or the ReAct loop.
 * - [AGENT_ACTION] / [UNKNOWN]: an action the model must plan, or an ambiguous input. Routed to
 *   the proven planning → safety → execute → ReAct path. [UNKNOWN] defaults here on purpose — a
 *   specific multi-step order the classifier does not recognize is still caught by the agent
 *   pipeline (owner-endorsed: chats skip the agent flow; specific orders do not).
 */
enum class IntentType { FAST_ACTION, CHAT, AGENT_ACTION, UNKNOWN }

/**
 * Deterministic, dependency-free intent router. Pure: takes the user text and the
 * (already-computed) rule-match [ToolCall] (`null` when no rule matched) and returns the lane, so
 * the routing rule is JVM-unit-testable without the orchestrator/Hilt/model.
 *
 * Safety posture: **conservative**. CHAT is claimed ONLY for question-shaped input that has no rule
 * match AND no phone-action / screen-reference intent — so an action order ("find the login button
 * and tap it", "read this page", "what's on my screen") always falls through to the agent pipeline.
 * Everything not confidently CHAT becomes [UNKNOWN] → agent. A rule match is trusted verbatim (it
 * is the deterministic offline parser): a DIRECT-tier rule tool is [FAST_ACTION], a CONFIRM/STRONG
 * rule tool is [AGENT_ACTION] (it still skips the LLM planner, but it is not "instant" — it needs a
 * confirmation tap).
 */
object IntentClassifier {

    /**
     * Rule-matched tools that are inert/launch-tier (DIRECT) — the genuinely instant actions that
     * need no confirmation. Must stay in sync with the DIRECT group in
     * [com.unoone.agent.safetyguard.SafetyGuard]; only the rule-reachable DIRECT tools are listed
     * (e.g. `speak_response`/`search_notes`/`summarize_text` are LLM-only, never produced by a rule).
     */
    private val DIRECT_RULE_TOOLS: Set<String> = setOf(
        "open_calendar", "check_calendar", "create_note", "deactivate_blind_aid",
        "open_chrome", "open_app"
    )

    /** Words that signal a phone action or screen reference — disqualify CHAT, route to the agent. */
    private val ACTION_WORDS: List<String> = listOf(
        "open", "launch", "create", "add", "delete", "remove", "clear", "erase", "send",
        "message", "call", "dial", "book", "schedule", "calendar", "note", "remember",
        "search", "find", "tap", "click", "scroll", "swipe", "press", "navigate", "go to",
        "go back", "go home", "read", "screen", "page", "browser", "camera", "blind",
        "whatsapp", "email", "mail", "pay", "payment", "password", "otp", "install",
        "type", "fill", "share", "url", "http", "www", "play", "remind", "detect", "scan",
        "ocr", "barrier", "obstacle", "skill", "voice record", "record"
    )

    /** Leading tokens that signal a conversational question. */
    private val QUESTION_STARTS: List<String> = listOf(
        "what ", "what's ", "what is ", "what are ", "why ", "how ", "how do ", "how does ",
        "how to ", "when ", "where ", "who ", "who is ", "explain ", "tell me ", "describe ",
        "define ", "translate ", "can you ", "could you ", "do you ", "are there ", "is it ",
        "does the ", "will the ", "should i ", "which "
    )

    /** Question markers used by supported Indian languages and common Latin transliterations. */
    private val MULTILINGUAL_QUESTION_MARKERS: List<String> = listOf(
        " kya ", " kya hai", " kya hey", " kaun ", " kyun ", " kyu ", " kaise ",
        " kab ", " kahan ", " kaha ",
        "क्या", "कौन", "क्यों", "कैसे", "कब", "कहाँ",
        "কী", "কি", "কেন", "কিয়", "কে", "কোন", "কখন", "কেতিয়া", "কোথায়", "ক'ত", "কিভাবে", "কেনেকৈ",
        "என்ன", "ஏன்", "யார்", "எப்போது", "எங்கே", "எப்படி",
        "ఏమి", "ఎందుకు", "ఎవరు", "ఎప్పుడు", "ఎక్కడ", "ఎలా",
        "ಏನು", "ಏಕೆ", "ಯಾರು", "ಯಾವಾಗ", "ಎಲ್ಲಿ", "ಹೇಗೆ",
        "എന്ത്", "എന്തുകൊണ്ട്", "ആര്", "എപ്പോൾ", "എവിടെ", "എങ്ങനെ"
    )

    fun classify(text: String, ruleMatch: ToolCall?): IntentType {
        if (ruleMatch != null) {
            // A rule match is the deterministic offline path — it skips the LLM planner and the
            // ReAct loop regardless of tier. Label DIRECT rule tools FAST_ACTION (genuinely
            // instant: no judge after A3, no confirmation) and CONFIRM/STRONG rule tools
            // AGENT_ACTION (still no LLM, but the user must confirm before execution).
            return if (ruleMatch.tool in DIRECT_RULE_TOOLS) IntentType.FAST_ACTION
            else IntentType.AGENT_ACTION
        }
        val lowered = text.lowercase().trim()
        if (lowered.isEmpty()) return IntentType.UNKNOWN

        // Any phone-action / screen-reference wording goes to the agent pipeline — never chat.
        if (ACTION_WORDS.any { lowered.contains(it) }) return IntentType.UNKNOWN

        // Question-shaped and action-free → conversational chat.
        val hasQuestionMark = lowered.contains("?")
        val startsWithQuestion = QUESTION_STARTS.any { lowered.startsWith(it) }
        val padded = " $lowered "
        val multilingualQuestion =
            MULTILINGUAL_QUESTION_MARKERS.any { marker -> padded.contains(marker) }
        return if (hasQuestionMark || startsWithQuestion || multilingualQuestion) {
            IntentType.CHAT
        } else IntentType.UNKNOWN
    }

    /** True when [ruleMatch] is a genuinely instant (DIRECT-tier) action. Exposed for diagnostics. */
    fun isDirectFastAction(ruleMatch: ToolCall?): Boolean =
        ruleMatch != null && ruleMatch.tool in DIRECT_RULE_TOOLS
}
