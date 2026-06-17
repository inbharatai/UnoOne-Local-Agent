package com.unoone.agent.safetyguard

import com.unoone.agent.core.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafetyGuardTest {

    private val guard = SafetyGuard()

    // === classify() — tool-level risk classification ===

    @Test
    fun directToolsClassifiedCorrectly() {
        val directTools = listOf(
            "create_note", "search_notes", "summarize_text", "speak_response",
            "open_chrome", "open_app", "deactivate_blind_aid", "check_calendar"
        )
        for (tool in directTools) {
            assertEquals("$tool should be DIRECT", RiskLevel.DIRECT, guard.classify(tool))
        }
    }

    @Test
    fun confirmToolsClassifiedCorrectly() {
        val confirmTools = listOf(
            "open_url", "open_calendar_insert", "open_dialer", "share_text",
            "read_screen", "ocr_screen", "open_camera", "create_skill"
        )
        for (tool in confirmTools) {
            assertEquals("$tool should be CONFIRM", RiskLevel.CONFIRM, guard.classify(tool))
        }
    }

    @Test
    fun strongConfirmToolsClassifiedCorrectly() {
        val strongConfirmTools = listOf(
            "delete_notes", "delete_all_notes", "export_data", "detect_objects",
            "draft_email", "send_whatsapp", "system_control", "find_and_click", "fill"
        )
        for (tool in strongConfirmTools) {
            assertEquals("$tool should be STRONG_CONFIRM", RiskLevel.STRONG_CONFIRM, guard.classify(tool))
        }
    }

    @Test
    fun blockedToolsClassifiedCorrectly() {
        val blockedTools = listOf(
            "send_message", "make_payment", "install_app", "access_passwords", "silent_control"
        )
        for (tool in blockedTools) {
            assertEquals("$tool should be BLOCK", RiskLevel.BLOCK, guard.classify(tool))
        }
    }

    @Test
    fun unknownToolDefaultsToStrongConfirm() {
        assertEquals("Unknown tool should default to STRONG_CONFIRM",
            RiskLevel.STRONG_CONFIRM, guard.classify("unknown_tool_xyz"))
    }

    // === classifyFromInput() — input-level risk overrides ===

    @Test
    fun inputWithDeleteAllUpgradedToStrongConfirm() {
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classifyFromInput("delete all notes"))
    }

    @Test
    fun inputWithPaymentBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("make a payment"))
    }

    @Test
    fun inputWithPasswordBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("show me my passwords"))
    }

    @Test
    fun inputWithInstallBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("install a new app"))
    }

    @Test
    fun inputWithBankBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("open my bank account"))
    }

    @Test
    fun inputWithCreditCardBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("what's my credit card number"))
    }

    @Test
    fun inputWithWireTransferBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("wire transfer money"))
    }

    @Test
    fun inputWithEraseUpgradedToStrongConfirm() {
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classifyFromInput("erase all data"))
    }

    @Test
    fun inputWithWipeUpgradedToStrongConfirm() {
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classifyFromInput("wipe the device"))
    }

    @Test
    fun inputWithDeleteUpgradedToConfirm() {
        assertEquals(RiskLevel.CONFIRM, guard.classifyFromInput("delete my note"))
    }

    @Test
    fun inputWithFactoryResetBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("factory reset the phone"))
    }

    @Test
    fun inputWithRemoveAccountUpgradedToStrongConfirm() {
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classifyFromInput("remove account"))
    }

    @Test
    fun normalInputClassifiedAsDirect() {
        assertEquals(RiskLevel.DIRECT, guard.classifyFromInput("what's the weather today"))
    }

    @Test
    fun inputWithSendBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("send message to John"))
    }

    @Test
    fun inputWithPayBlocked() {
        assertEquals(RiskLevel.BLOCK, guard.classifyFromInput("pay the bill"))
    }

    // === Risk override: input risk > tool risk ===

    @Test
    fun inputRiskOverridesToolRiskWhenHigher() {
        // "create_note" is DIRECT, but input "delete all" should upgrade to STRONG_CONFIRM
        val toolRisk = guard.classify("create_note")
        val inputRisk = guard.classifyFromInput("delete all my notes")
        assert(inputRisk.ordinal > toolRisk.ordinal)
    }

    @Test
    fun inputRiskDoesNotOverrideWhenToolRiskHigher() {
        // "send_whatsapp" is STRONG_CONFIRM, input "open whatsapp" is DIRECT
        val toolRisk = guard.classify("send_whatsapp")
        val inputRisk = guard.classifyFromInput("open whatsapp for me")
        assert(toolRisk.ordinal > inputRisk.ordinal)
    }
}