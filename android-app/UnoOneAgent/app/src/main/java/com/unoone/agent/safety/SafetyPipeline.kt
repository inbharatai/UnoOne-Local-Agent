package com.unoone.agent.safety

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.unoone.agent.core.interfaces.ISafetyPipeline
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.safety.PermissionRequirement
import com.unoone.agent.core.safety.ToolPermissionRegistry
import com.unoone.agent.core.util.Logger
import com.unoone.agent.safetyguard.SafetyGuard

/**
 * Handles permission checks, risk classification, and confirmation flows.
 * Extracted from AgentOrchestrator to separate safety concerns from orchestration.
 */
class SafetyPipeline(
    private val context: Context,
    private val safetyGuard: SafetyGuard
) : ISafetyPipeline {

    /**
     * Missing *runtime* (dangerous) permission strings for a tool. Kept as `List<String>` for
     * back-compat with the orchestrator's `requestPermissions` flow. Use [unsatisfiedRequirements]
     * for the full picture including Overlay/Accessibility/MediaProjection.
     */
    override fun checkPermissionsForTool(tool: String): List<String> {
        return ToolPermissionRegistry.runtimePermissionsFor(tool).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * All unsatisfied [PermissionRequirement]s for a tool (runtime + system access). The orchestrator
     * uses this to decide which access to request — runtime perms via requestPermissions,
     * accessibility/overlay via system settings intents, MediaProjection via the screenshot
     * permission activity. Empty ⇒ fully authorized.
     */
    fun unsatisfiedRequirements(tool: String): List<PermissionRequirement> =
        ToolPermissionRegistry.requirementsFor(tool).filterNot { isSatisfied(it) }

    /** True if the given requirement is currently granted/enabled on this device. */
    fun isSatisfied(requirement: PermissionRequirement): Boolean =
        com.unoone.agent.PermissionManager.isRequirementSatisfied(context, requirement)

    /**
     * Classify a tool + raw input combination into a risk level.
     * If the input-level risk exceeds the tool-level risk, the input risk overrides.
     */
    override fun classifyRisk(tool: String, rawInput: String): RiskLevel {
        var riskLevel = safetyGuard.classify(tool)

        // Input-level risk check: upgrade if raw input contains dangerous keywords
        val inputRisk = safetyGuard.classifyFromInput(rawInput)
        if (inputRisk.ordinal > riskLevel.ordinal) {
            Logger.w("SafetyPipeline: Input risk (${inputRisk.name}) overrides tool risk (${riskLevel.name})")
            riskLevel = inputRisk
        }
        return riskLevel
    }

    /**
     * Returns whether a tool at the given risk level requires explicit user confirmation.
     */
    override fun requiresConfirmation(riskLevel: RiskLevel): Boolean {
        return riskLevel == RiskLevel.CONFIRM || riskLevel == RiskLevel.STRONG_CONFIRM
    }

    /**
     * Returns whether a tool at the given risk level is blocked entirely.
     */
    override fun isBlocked(riskLevel: RiskLevel): Boolean {
        return riskLevel == RiskLevel.BLOCK
    }

    /**
     * Generates a confirmation message for the given tool and risk level.
     */
    override fun confirmationMessage(tool: String, riskLevel: RiskLevel): String {
        return when (riskLevel) {
            RiskLevel.STRONG_CONFIRM -> "SECURITY CHECK: This action ($tool) is sensitive. Confirm?"
            RiskLevel.CONFIRM -> "Confirm: Execute $tool?"
            else -> "Confirm?"
        }
    }
}