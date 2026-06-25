package com.unoone.agent.safetyguard

import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.util.Logger

class SafetyGuard {

    private val riskRules = mapOf(
        // Risk 0 — Direct execution (no confirmation needed)
        "create_note" to RiskLevel.DIRECT,
        "search_notes" to RiskLevel.DIRECT,
        "summarize_text" to RiskLevel.DIRECT,
        "speak_response" to RiskLevel.DIRECT,
        "open_chrome" to RiskLevel.DIRECT,
        "open_app" to RiskLevel.DIRECT,
        "deactivate_blind_aid" to RiskLevel.DIRECT,
        "check_calendar" to RiskLevel.DIRECT,

        // Risk 1 — Single confirmation
        "open_url" to RiskLevel.CONFIRM,
        "open_calendar_insert" to RiskLevel.CONFIRM,
        "open_dialer" to RiskLevel.CONFIRM,
        "share_text" to RiskLevel.CONFIRM,
        "read_screen" to RiskLevel.CONFIRM,
        "ocr_screen" to RiskLevel.CONFIRM,
        "open_camera" to RiskLevel.CONFIRM,
        "create_skill" to RiskLevel.CONFIRM,
        "long_press" to RiskLevel.CONFIRM,
        "click" to RiskLevel.CONFIRM,
        "type" to RiskLevel.CONFIRM,

        // Risk 2 — Strong confirmation (must type "confirm")
        "delete_notes" to RiskLevel.STRONG_CONFIRM,
        "delete_all_notes" to RiskLevel.STRONG_CONFIRM,
        "export_data" to RiskLevel.STRONG_CONFIRM,
        "detect_objects" to RiskLevel.STRONG_CONFIRM,
        "draft_email" to RiskLevel.STRONG_CONFIRM,
        "send_whatsapp" to RiskLevel.STRONG_CONFIRM,
        "system_control" to RiskLevel.STRONG_CONFIRM,
        "find_and_click" to RiskLevel.STRONG_CONFIRM,
        "fill" to RiskLevel.STRONG_CONFIRM,

        // Risk 3 — Block (never executed)
        "send_message" to RiskLevel.BLOCK,
        "make_payment" to RiskLevel.BLOCK,
        "install_app" to RiskLevel.BLOCK,
        "access_passwords" to RiskLevel.BLOCK,
        "silent_control" to RiskLevel.BLOCK
    )

    fun classify(toolName: String): RiskLevel {
        val level = riskRules[toolName] ?: RiskLevel.STRONG_CONFIRM
        Logger.d("SafetyGuard classified $toolName as ${level.name}")
        return level
    }

    /**
     * Input-level risk classification. Scans the raw user input for dangerous keywords
     * that might indicate higher risk than the tool name alone suggests.
     * Used as a secondary check after tool-level classification.
     *
     * NOTE (review 2026-06-24): the broad keyword→BLOCK escalations here (send/message/bank/
     * password/install/credit card) are the project's deliberate, tested security posture
     * (see SafetyGuardTest). They can over-block legitimately-worded requests that route to an
     * already-STRONG_CONFIRM tool — e.g. "send a WhatsApp message to mom" hits "send "/"message"
     * → BLOCK, which overrides send_whatsapp's STRONG_CONFIRM and hard-blocks it. That is a known
     * UX trade-off, left in place because the tested intent is to block on these keywords; weakening
     * it is a security-policy decision for the user, not a bug fix.
     */
    fun classifyFromInput(input: String): RiskLevel {
        val lowered = input.lowercase()
        return when {
            // Block-level keywords
            lowered.contains("delete all") -> RiskLevel.STRONG_CONFIRM
            lowered.contains("send ") || lowered.contains("message") -> RiskLevel.BLOCK
            lowered.contains("payment") || lowered.contains("pay ") -> RiskLevel.BLOCK
            lowered.contains("password") -> RiskLevel.BLOCK
            lowered.contains("install") -> RiskLevel.BLOCK
            lowered.contains("erase") || lowered.contains("wipe") -> RiskLevel.STRONG_CONFIRM
            lowered.contains("transfer money") || lowered.contains("wire transfer") -> RiskLevel.BLOCK
            lowered.contains("bank") || lowered.contains("credit card") -> RiskLevel.BLOCK

            // Strong confirmation keywords
            lowered.contains("delete") && !lowered.contains("delete all") -> RiskLevel.CONFIRM
            lowered.contains("remove account") -> RiskLevel.STRONG_CONFIRM
            lowered.contains("format") || lowered.contains("factory reset") -> RiskLevel.BLOCK

            else -> RiskLevel.DIRECT
        }
    }
}