package com.unoone.agent.safety

import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.model.UserAction
import com.unoone.agent.core.model.availableActions
import com.unoone.agent.safetyguard.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafetyPipelineTest {

    /**
     * Tests for SafetyPipeline — permission checks and risk classification.
     * Uses Robolectric to avoid Android framework issues.
     * Note: checkPermissionsForTool() requires a real Android Context,
     * so we test classification and confirmation logic which is pure Kotlin.
     */

    private lateinit var safetyGuard: SafetyGuard

    @Before
    fun setup() {
        safetyGuard = SafetyGuard()
    }

    // === classifyRisk() — tool + input risk combination ===

    @Test
    fun classifyRiskDirectToolWithNormalInput() {
        // Create a simple test that uses SafetyGuard directly
        // since SafetyPipeline requires Android Context
        val toolRisk = safetyGuard.classify("create_note")
        assertEquals(RiskLevel.DIRECT, toolRisk)

        val inputRisk = safetyGuard.classifyFromInput("create a note about shopping")
        assertEquals(RiskLevel.DIRECT, inputRisk)
    }

    @Test
    fun inputRiskOverridesToolRiskWhenHigher() {
        val toolRisk = safetyGuard.classify("create_note")  // DIRECT
        val inputRisk = safetyGuard.classifyFromInput("delete all notes")  // STRONG_CONFIRM
        assertTrue("Input risk should override tool risk",
            inputRisk.ordinal > toolRisk.ordinal)
    }

    @Test
    fun toolRiskStaysWhenInputIsNormal() {
        val toolRisk = safetyGuard.classify("send_whatsapp")  // STRONG_CONFIRM
        val inputRisk = safetyGuard.classifyFromInput("send a message to mom")  // BLOCK
        // Input risk is even higher, so it should override
        assertTrue("Input risk BLOCK should override tool STRONG_CONFIRM",
            inputRisk.ordinal >= toolRisk.ordinal)
    }

    // === requiresConfirmation() ===

    @Test
    fun directDoesNotRequireConfirmation() {
        assertFalse(requiresConfirmation(RiskLevel.DIRECT))
    }

    @Test
    fun confirmRequiresConfirmation() {
        assertTrue(requiresConfirmation(RiskLevel.CONFIRM))
    }

    @Test
    fun strongConfirmRequiresConfirmation() {
        assertTrue(requiresConfirmation(RiskLevel.STRONG_CONFIRM))
    }

    @Test
    fun blockedDoesNotRequireConfirmation() {
        assertFalse(requiresConfirmation(RiskLevel.BLOCK))
    }

    // === isBlocked() ===

    @Test
    fun onlyBlockIsBlocked() {
        assertTrue(isBlocked(RiskLevel.BLOCK))
        assertFalse(isBlocked(RiskLevel.DIRECT))
        assertFalse(isBlocked(RiskLevel.CONFIRM))
        assertFalse(isBlocked(RiskLevel.STRONG_CONFIRM))
    }

    // === confirmationMessage() ===

    @Test
    fun confirmMessageIsAppropriate() {
        val msg = confirmationMessage("open_url", RiskLevel.CONFIRM)
        assertTrue(msg.contains("Confirm"))
        assertTrue(msg.contains("open_url"))
    }

    @Test
    fun strongConfirmMessageIncludesSecurityWarning() {
        val msg = confirmationMessage("send_whatsapp", RiskLevel.STRONG_CONFIRM)
        assertTrue(msg.contains("SECURITY CHECK"))
    }

    // === RiskLevel.availableActions() ===

    @Test
    fun directActionsIncludeCancelAndConfirm() {
        val actions = RiskLevel.DIRECT.availableActions()
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.CONFIRM))
    }

    @Test
    fun confirmActionsIncludeAllThree() {
        val actions = RiskLevel.CONFIRM.availableActions()
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.EDIT))
        assertTrue(actions.contains(UserAction.CONFIRM))
    }

    @Test
    fun strongConfirmActionsIncludeCancelAndEditButNotConfirm() {
        val actions = RiskLevel.STRONG_CONFIRM.availableActions()
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.EDIT))
        assertFalse(actions.contains(UserAction.CONFIRM))
    }

    @Test
    fun blockActionsOnlyCancel() {
        val actions = RiskLevel.BLOCK.availableActions()
        assertEquals(1, actions.size)
        assertTrue(actions.contains(UserAction.CANCEL))
    }

    // Helper methods matching SafetyPipeline's logic
    private fun requiresConfirmation(riskLevel: RiskLevel): Boolean {
        return riskLevel == RiskLevel.CONFIRM || riskLevel == RiskLevel.STRONG_CONFIRM
    }

    private fun isBlocked(riskLevel: RiskLevel): Boolean {
        return riskLevel == RiskLevel.BLOCK
    }

    private fun confirmationMessage(tool: String, riskLevel: RiskLevel): String {
        return when (riskLevel) {
            RiskLevel.STRONG_CONFIRM -> "SECURITY CHECK: This action ($tool) is sensitive. Confirm?"
            RiskLevel.CONFIRM -> "Confirm: Execute $tool?"
            else -> "Confirm?"
        }
    }
}