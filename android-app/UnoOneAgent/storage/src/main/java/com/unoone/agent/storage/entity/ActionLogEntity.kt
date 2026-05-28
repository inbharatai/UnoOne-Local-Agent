package com.unoone.agent.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val inputText: String,
    val inputType: String = "voice",
    val selectedTool: String = "",
    val toolArgsJson: String = "{}",
    val riskLevel: Int = 0,
    val status: String = "success", // success, failed, blocked
    val errorMessage: String? = null,
    val sttLatencyMs: Long? = null,
    val modelLatencyMs: Long? = null,
    val ttsLatencyMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
