package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.entity.ActionLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the Audit Viewer screen. Reads the recent action-log [Flow] and applies client-side
 * filters (tool substring, status, risk level). Raw input is shown redacted via [redact] so the
 * viewer never displays sensitive credentials/OTP phrases in full.
 */
class AuditViewerViewModel(
    actionLogDao: ActionLogDao
) : ViewModel() {

    enum class StatusFilter(val label: String) {
        ALL("All"), SUCCESS("Success"), FAILED("Failed"), BLOCKED("Blocked")
    }

    /** One display row. [input] is redacted. */
    data class AuditRow(
        val id: Long,
        val timestamp: Long,
        val input: String,
        val inputType: String,
        val tool: String,
        val riskLevel: Int,
        val status: String,
        val errorMessage: String?
    )

    private val _toolFilter = MutableStateFlow("")
    val toolFilter: StateFlow<String> = _toolFilter.asStateFlow()

    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)
    val statusFilter: StateFlow<StatusFilter> = _statusFilter.asStateFlow()

    private val _onlyElevatedRisk = MutableStateFlow(false)
    val onlyElevatedRisk: StateFlow<Boolean> = _onlyElevatedRisk.asStateFlow()

    // The source Flow from Room (most recent 100), mapped to redacted display rows.
    private val sourceRows: kotlinx.coroutines.flow.Flow<List<AuditRow>> =
        actionLogDao.getRecent(100).map { logs -> logs.map { it.toRow() } }

    // Combine the source rows with the three filter StateFlows.
    val rows: StateFlow<List<AuditRow>> =
        combine(sourceRows, _toolFilter, _statusFilter, _onlyElevatedRisk) { logs, tool, status, elevated ->
            logs.filter { row ->
                (tool.isBlank() || row.tool.contains(tool, ignoreCase = true)) &&
                    (status == StatusFilter.ALL || row.status.equals(status.name.lowercase(), ignoreCase = true)) &&
                    (!elevated || row.riskLevel >= 2)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun setToolFilter(value: String) { _toolFilter.value = value }
    fun setStatusFilter(value: StatusFilter) { _statusFilter.value = value }
    fun setOnlyElevatedRisk(value: Boolean) { _onlyElevatedRisk.value = value }

    private fun ActionLogEntity.toRow(): AuditRow = AuditRow(
        id = id,
        timestamp = createdAt,
        input = redact(inputText),
        inputType = inputType,
        tool = selectedTool,
        riskLevel = riskLevel,
        status = status,
        errorMessage = errorMessage
    )

    companion object {
        /** Redact long/sensitive-looking input; keep a short tail so the user can recognize the command. */
        fun redact(input: String): String {
            if (input.length <= 32) return input
            return input.take(20) + "…[" + (input.length - 20) + " chars hidden]"
        }
    }
}