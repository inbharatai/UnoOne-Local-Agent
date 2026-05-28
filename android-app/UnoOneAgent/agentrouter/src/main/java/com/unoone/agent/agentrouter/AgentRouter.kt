package com.unoone.agent.agentrouter

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger

class AgentRouter {

    private val registry = mutableMapOf<String, ToolHandler>()

    init {
        // Register built-in tools
        register("create_note", CreateNoteHandler())
        register("search_notes", SearchNotesHandler())
        register("speak_response", SpeakResponseHandler())
        register("open_chrome", OpenChromeHandler())
        register("open_url", OpenUrlHandler())
        register("open_app", OpenAppHandler())
        register("open_calendar_insert", OpenCalendarHandler())
    }

    fun register(name: String, handler: ToolHandler) {
        registry[name] = handler
        Logger.d("Registered tool: $name")
    }

    fun route(toolCall: ToolCall): Result<String> {
        val handler = registry[toolCall.tool]
            ?: return Result.Error("Unknown tool: ${toolCall.tool}")
        return handler.execute(toolCall.args)
    }

    fun validateToolName(name: String): Boolean = registry.containsKey(name)

    interface ToolHandler {
        fun execute(args: kotlinx.serialization.json.JsonObject): Result<String>
    }

    // Stubs
    inner class CreateNoteHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing create_note")
            return Result.Success("Note created")
        }
    }
    inner class SearchNotesHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing search_notes")
            return Result.Success("Notes found")
        }
    }
    inner class SpeakResponseHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing speak_response")
            return Result.Success("Speaking")
        }
    }
    inner class OpenChromeHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing open_chrome")
            return Result.Success("Chrome opened")
        }
    }
    inner class OpenUrlHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing open_url")
            return Result.Success("URL opened")
        }
    }
    inner class OpenAppHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing open_app")
            return Result.Success("App opened")
        }
    }
    inner class OpenCalendarHandler : ToolHandler {
        override fun execute(args: kotlinx.serialization.json.JsonObject): Result<String> {
            Logger.d("Executing open_calendar_insert")
            return Result.Success("Calendar opened")
        }
    }
}
