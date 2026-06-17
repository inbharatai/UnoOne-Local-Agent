package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall

/**
 * Abstraction for tool execution. Enables testing with mock executors
 * and swapping implementations without touching the orchestrator.
 */
interface IActionExecutor {
    suspend fun executeTool(toolCall: ToolCall): Result<String>
    fun getRequiredPermissionsForTool(tool: String): List<String>
}