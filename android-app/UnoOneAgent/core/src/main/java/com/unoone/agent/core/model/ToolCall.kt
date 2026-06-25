package com.unoone.agent.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonObject
)

/**
 * Expands a `compound` ToolCall into its ordered sub-calls (each a real [ToolCall]).
 *
 * Non-compound calls — or a compound with a missing/malformed `steps` array — return a
 * single-element list containing this call, so callers can treat the result uniformly:
 * `for (step in toolCall.compoundSteps()) { ... }` works for both simple and compound commands.
 */
fun ToolCall.compoundSteps(): List<ToolCall> {
    if (tool != "compound") return listOf(this)
    val steps = args["steps"] ?: return listOf(this)
    val array = steps as? JsonArray ?: return listOf(this)
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val toolName = obj["tool"] as? JsonPrimitive ?: return@mapNotNull null
        val stepArgs = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
        ToolCall(toolName.content, stepArgs)
    }.ifEmpty { listOf(this) }
}

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
