package com.unoone.agent.core.model

import kotlinx.serialization.json.*

/** Fail-closed contract at every executor boundary; no primitive coercion or plugin fallback. */
object ToolCallValidator {
    private val navigation = setOf("go_back", "go_home", "scroll_down", "scroll_up", "open_notifications", "open_recents")
    private val mutations = setOf("click", "type", "fill", "swipe", "long_press", "find_and_click")

    /** Explicit compatibility adapter for the historical pipe-delimited rule-parser output.
     * It never changes the canonical schema, drops malformed steps, or executes a step.
     */
    fun adaptLegacySkill(call: ToolCall): ToolCall {
        val steps = call.args["steps"] as? JsonPrimitive ?: return call
        if (call.tool != "create_skill" || !steps.isString) return call
        val parts = steps.content.split('|').map { it.trim() }
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() }) { "Skill steps must not be empty" }
        return call.copy(args = JsonObject(call.args + ("steps" to JsonArray(parts.map { JsonPrimitive(it) }))))
    }

    /** Returns a human-readable rejection, null only for a valid executable contract. */
    fun rejection(call: ToolCall): String? {
        val schema = CanonicalToolRegistry.schemaFor(call.tool) ?: return "Unknown tool: ${call.tool}"
        val unknown = call.args.keys - schema.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return "Unknown arguments: ${unknown.sorted()}"
        for (param in schema.params) {
            val value = call.args[param.name]
            if (value == null) {
                if (param.required) return "Missing argument: ${param.name}"
                continue
            }
            val primitive = value as? JsonPrimitive
            val valid = when (param.type) {
                ToolParamType.STRING -> primitive != null && primitive.isString
                ToolParamType.INT -> primitive != null && !primitive.isString && primitive.intOrNull != null
                ToolParamType.BOOLEAN -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                ToolParamType.FLOAT -> primitive != null && !primitive.isString && primitive.floatOrNull?.isFinite() == true
                ToolParamType.DOUBLE -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                ToolParamType.STRING_LIST -> value is JsonArray && value.all { it is JsonPrimitive && it.isString }
            }
            if (!valid) return "Invalid type for ${param.name}: expected ${param.type}"
        }
        if (call.tool == "create_skill") {
            val steps = call.args.getValue("steps") as JsonArray
            if (steps.isEmpty() || steps.any { it.jsonPrimitive.content.isBlank() }) return "Skill steps must not be empty"
        }
        if (call.tool == "prepare_document_fill" && call.args.getValue("format").jsonPrimitive.content.lowercase(java.util.Locale.ROOT) !in setOf("pdf", "docx")) return "Document format must be pdf or docx"
        if (call.tool == "voice_recording") {
            val duration = call.args["duration_seconds"]?.jsonPrimitive?.intOrNull
            if (duration != null && duration !in 1..30) return "Recording duration must be 1..30 seconds"
        }
        if (call.tool == "system_control") {
            val action = call.args.getValue("action").jsonPrimitive.content
            if (action in mutations) return "Manual handover required: generic mutations need native semantic safety checks; use the grounded device action route."
            if (action !in navigation) return "Unknown or unsupported system action: $action"
            if (call.args.keys != setOf("action")) return "Navigation action accepts no target, value or coordinates"
        }
        return null
    }
}
