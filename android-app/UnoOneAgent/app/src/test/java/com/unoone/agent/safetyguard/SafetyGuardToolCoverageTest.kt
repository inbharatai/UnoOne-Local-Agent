package com.unoone.agent.safetyguard

import com.unoone.agent.core.model.RiskLevel
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that every tool the Gemma planner can emit has a corresponding
 * risk classification in [SafetyGuard]. An unclassified tool would default to
 * STRONG_CONFIRM, which is safe but may confuse users; this test documents
 * the intended tier for each capability.
 */
@RunWith(RobolectricTestRunner::class)
class SafetyGuardToolCoverageTest {

    private val guard = SafetyGuard()

    @Test
    fun allGemmaToolsHaveExplicitRiskClassification() {
        val expected = mapOf(
            "create_note" to RiskLevel.DIRECT,
            "search_notes" to RiskLevel.DIRECT,
            "summarize_text" to RiskLevel.DIRECT,
            "speak_response" to RiskLevel.DIRECT,
            "open_chrome" to RiskLevel.DIRECT,
            "open_app" to RiskLevel.DIRECT,
            "open_url" to RiskLevel.CONFIRM,
            "open_camera" to RiskLevel.CONFIRM,
            "system_control" to RiskLevel.STRONG_CONFIRM,
            "read_screen" to RiskLevel.CONFIRM,
            "ocr_screen" to RiskLevel.CONFIRM,
            "create_skill" to RiskLevel.CONFIRM,
            "draft_email" to RiskLevel.STRONG_CONFIRM,
            "send_whatsapp" to RiskLevel.STRONG_CONFIRM,
            "check_calendar" to RiskLevel.DIRECT,
            "open_calendar" to RiskLevel.DIRECT,
            "open_calendar_insert" to RiskLevel.CONFIRM,
            "open_dialer" to RiskLevel.CONFIRM,
            "share_text" to RiskLevel.CONFIRM,
            "delete_notes" to RiskLevel.STRONG_CONFIRM,
            "delete_all_notes" to RiskLevel.STRONG_CONFIRM,
            "export_data" to RiskLevel.STRONG_CONFIRM,
            "detect_objects" to RiskLevel.STRONG_CONFIRM,
            "deactivate_blind_aid" to RiskLevel.DIRECT,
            "voice_recording" to RiskLevel.CONFIRM,   // mic capture → single confirmation
            "web_search" to RiskLevel.CONFIRM,         // online lookup → single confirmation
            "describe_scene" to RiskLevel.STRONG_CONFIRM,  // captures + analyzes the screen
            "secure_browser_task" to RiskLevel.CONFIRM,  // drives the Secure Browser on an approved origin
            "prepare_document_fill" to RiskLevel.DIRECT // picker only; save remains explicit
        )

        for ((tool, expectedLevel) in expected) {
            val actual = guard.classify(tool)
            if (actual != expectedLevel) {
                fail("Tool '$tool' expected $expectedLevel but was $actual")
            }
        }
    }

    @Test
    fun destructiveToolsRequireStrongConfirmationOrBlock() {
        val destructiveTools = listOf(
            "delete_notes", "delete_all_notes", "export_data",
            "detect_objects", "draft_email", "send_whatsapp", "system_control"
        )
        for (tool in destructiveTools) {
            val level = guard.classify(tool)
            check(level == RiskLevel.STRONG_CONFIRM || level == RiskLevel.BLOCK) {
                "Destructive tool '$tool' must be STRONG_CONFIRM or BLOCK, was $level"
            }
        }
    }
}
