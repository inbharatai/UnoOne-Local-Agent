package com.unoone.agent.safetyguard

import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.util.Logger

class SafetyGuard {

    private val riskRules = mapOf(
        // Risk 0 — Direct execution
        "create_note" to RiskLevel.DIRECT,
        "search_notes" to RiskLevel.DIRECT,
        "summarize_text" to RiskLevel.DIRECT,
        "speak_response" to RiskLevel.DIRECT,
        "open_chrome" to RiskLevel.DIRECT,
        "open_app" to RiskLevel.DIRECT,

        // Risk 1 — Confirmation
        "open_url" to RiskLevel.CONFIRM,
        "open_calendar_insert" to RiskLevel.CONFIRM,
        "open_dialer" to RiskLevel.CONFIRM,
        "share_text" to RiskLevel.CONFIRM,

        // Risk 2 — Strong confirmation
        "delete_notes" to RiskLevel.STRONG_CONFIRM,
        "delete_all_notes" to RiskLevel.STRONG_CONFIRM,
        "export_data" to RiskLevel.STRONG_CONFIRM,

        // Risk 3 — Block
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

    fun classifyFromInput(input: String): RiskLevel {
        val lowered = input.lowercase()
        return when {
            lowered.contains("delete all") -> RiskLevel.STRONG_CONFIRM
            lowered.contains("send ") || lowered.contains("message") -> RiskLevel.BLOCK
            lowered.contains("payment") || lowered.contains("pay") -> RiskLevel.BLOCK
            lowered.contains("password") -> RiskLevel.BLOCK
            lowered.contains("install") -> RiskLevel.BLOCK
            else -> RiskLevel.DIRECT
        }
    }
}
