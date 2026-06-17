package com.unoone.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAssessmentTest {

    @Test
    fun directRiskAvailableActions() {
        val actions = RiskLevel.DIRECT.availableActions()
        assertEquals(2, actions.size)
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.CONFIRM))
        assertFalse(actions.contains(UserAction.EDIT))
    }

    @Test
    fun confirmRiskAvailableActions() {
        val actions = RiskLevel.CONFIRM.availableActions()
        assertEquals(3, actions.size)
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.EDIT))
        assertTrue(actions.contains(UserAction.CONFIRM))
    }

    @Test
    fun strongConfirmRiskAvailableActions() {
        val actions = RiskLevel.STRONG_CONFIRM.availableActions()
        assertEquals(2, actions.size)
        assertTrue(actions.contains(UserAction.CANCEL))
        assertTrue(actions.contains(UserAction.EDIT))
        assertFalse(actions.contains(UserAction.CONFIRM))
    }

    @Test
    fun blockRiskAvailableActions() {
        val actions = RiskLevel.BLOCK.availableActions()
        assertEquals(1, actions.size)
        assertTrue(actions.contains(UserAction.CANCEL))
    }

    @Test
    fun riskAssessmentDataClass() {
        val assessment = RiskAssessment(
            level = RiskLevel.CONFIRM,
            availableActions = setOf(UserAction.CANCEL, UserAction.EDIT, UserAction.CONFIRM),
            editField = "message"
        )
        assertEquals(RiskLevel.CONFIRM, assessment.level)
        assertEquals(3, assessment.availableActions.size)
        assertEquals("message", assessment.editField)
    }

    @Test
    fun riskAssessmentWithNullEditField() {
        val assessment = RiskAssessment(
            level = RiskLevel.BLOCK,
            availableActions = setOf(UserAction.CANCEL)
        )
        assertEquals(null, assessment.editField)
    }

    @Test
    fun riskLevelOrdering() {
        assertTrue(RiskLevel.DIRECT.ordinal < RiskLevel.CONFIRM.ordinal)
        assertTrue(RiskLevel.CONFIRM.ordinal < RiskLevel.STRONG_CONFIRM.ordinal)
        assertTrue(RiskLevel.STRONG_CONFIRM.ordinal < RiskLevel.BLOCK.ordinal)
    }
}