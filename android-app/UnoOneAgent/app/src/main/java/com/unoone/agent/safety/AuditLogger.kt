package com.unoone.agent.safety

import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.entity.ActionLogEntity
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Audit logger that records every safety decision to the database.
 * Stores input hashes (SHA-256) instead of raw text for privacy.
 * Never blocks the calling thread — all writes are async on Dispatchers.IO.
 */
object AuditLogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var actionLogDao: ActionLogDao? = null

    fun initialize(actionLogDao: ActionLogDao) {
        this.actionLogDao = actionLogDao
    }

    /**
     * Log a safety decision.
     * @param action The tool/action name
     * @param riskLevel The risk classification
     * @param outcome What happened: "approved", "denied", "blocked", "confirmed", "cancelled"
     * @param rawInput The original user input (will be hashed, not stored in cleartext)
     */
    fun log(action: String, riskLevel: RiskLevel, outcome: String, rawInput: String) {
        val dao = actionLogDao ?: run {
            Logger.w("AuditLogger: Not initialized — dropping audit log")
            return
        }
        val inputHash = sha256(rawInput)
        val log = ActionLogEntity(
            inputText = inputHash,
            inputType = "audit",
            selectedTool = action,
            riskLevel = riskLevel.ordinal,
            status = outcome
        )
        scope.launch {
            try {
                dao.insert(log)
            } catch (e: Exception) {
                Logger.e("AuditLogger: Failed to write audit log", e)
            }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}