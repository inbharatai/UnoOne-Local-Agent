package com.unoone.agent.agentrouter

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin registry for tool handlers.
 *
 * Built-in tools (create_note, open_chrome, etc.) are handled directly by AgentOrchestrator.
 * This registry is for registering additional tool handlers at runtime — e.g., from
 * skill modules or future plugins. Unknown tools that are not registered here will
 * return Result.Error("Unknown tool").
 */
class AgentRouter {

    private val registry = ConcurrentHashMap<String, ToolHandler>()

    fun register(name: String, handler: ToolHandler) {
        registry[name] = handler
        Logger.d("AgentRouter: Registered tool: $name")
    }

    fun unregister(name: String) {
        registry.remove(name)
        Logger.d("AgentRouter: Unregistered tool: $name")
    }

    fun route(toolCall: ToolCall): Result<String> {
        val handler = registry[toolCall.tool]
            ?: return Result.Error("Unknown tool: ${toolCall.tool}. No handler registered for '${toolCall.tool}'.")
        return handler.execute(toolCall.args)
    }

    fun validateToolName(name: String): Boolean = registry.containsKey(name)

    fun listRegisteredTools(): Set<String> = registry.keys.toSet()

    interface ToolHandler {
        fun execute(args: kotlinx.serialization.json.JsonObject): Result<String>
    }
}