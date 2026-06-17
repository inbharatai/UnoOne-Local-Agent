package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.RiskLevel

/**
 * Abstraction for safety checks: permission verification, risk classification, and confirmation.
 * Enables testing safety logic without Android framework dependencies.
 */
interface ISafetyPipeline {
    fun checkPermissionsForTool(tool: String): List<String>
    fun classifyRisk(tool: String, rawInput: String): RiskLevel
    fun requiresConfirmation(riskLevel: RiskLevel): Boolean
    fun isBlocked(riskLevel: RiskLevel): Boolean
    fun confirmationMessage(tool: String, riskLevel: RiskLevel): String
}