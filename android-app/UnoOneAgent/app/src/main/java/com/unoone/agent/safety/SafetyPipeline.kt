package com.unoone.agent.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unoone.agent.core.interfaces.ISafetyPipeline
import com.unoone.agent.core.model.RiskLevel
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
     * Checks whether all required permissions are granted for the given tool.
     * Returns a list of missing permissions (empty if all granted).
     */
    override fun checkPermissionsForTool(tool: String): List<String> {
        return getRequiredPermissionsForTool(tool).filter { perm ->
            if (perm == Manifest.permission.SYSTEM_ALERT_WINDOW) {
                !Settings.canDrawOverlays(context)
            } else {
                PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, perm)
            }
        }
    }

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

    private fun getRequiredPermissionsForTool(tool: String): List<String> {
        return when (tool) {
            "create_note" -> emptyList()
            "draft_email" -> emptyList()
            "send_whatsapp" -> emptyList()
            "check_calendar" -> listOf(Manifest.permission.READ_CALENDAR)
            "open_calendar_insert" -> listOf(Manifest.permission.WRITE_CALENDAR)
            "open_camera" -> listOf(Manifest.permission.CAMERA)
            "ocr_screen", "read_screen" -> listOf(Manifest.permission.SYSTEM_ALERT_WINDOW)
            "system_control" -> listOf(Manifest.permission.SYSTEM_ALERT_WINDOW)
            "voice_recording" -> listOf(Manifest.permission.RECORD_AUDIO)
            "detect_objects" -> listOf(Manifest.permission.CAMERA)
            "deactivate_blind_aid" -> emptyList()
            else -> emptyList()
        }
    }
}