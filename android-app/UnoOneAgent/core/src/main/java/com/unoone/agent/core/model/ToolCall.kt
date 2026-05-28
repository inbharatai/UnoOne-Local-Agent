package com.unoone.agent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonObject
)

@Serializable
data class AgentCommand(
    val inputText: String,
    val inputType: InputType = InputType.VOICE
)

enum class InputType {
    VOICE,
    TEXT
}

enum class AgentStatus {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    UNDERSTANDING,
    TOOL_SELECTED,
    SAFETY_CHECK,
    EXECUTING,
    VERIFYING,
    SPEAKING,
    DONE,
    FAILED
}

enum class RiskLevel(val level: Int) {
    DIRECT(0),
    CONFIRM(1),
    STRONG_CONFIRM(2),
    BLOCK(3)
}
