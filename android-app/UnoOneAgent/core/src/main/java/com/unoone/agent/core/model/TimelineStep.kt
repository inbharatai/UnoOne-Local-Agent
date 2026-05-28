package com.unoone.agent.core.model

data class TimelineStep(
    val status: AgentStatus,
    val label: String,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)
