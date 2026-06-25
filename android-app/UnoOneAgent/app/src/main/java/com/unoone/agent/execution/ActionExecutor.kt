package com.unoone.agent.execution

import android.content.Context
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.interfaces.IActionExecutor
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.safety.ToolPermissionRegistry
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.util.TextSummarizer
import com.unoone.agent.data.DataExporter
import com.unoone.agent.phonecontrol.CalendarControl
import com.unoone.agent.phonecontrol.OcrControl
import com.unoone.agent.phonecontrol.PackageResolver
import com.unoone.agent.phonecontrol.PhoneControl
import com.unoone.agent.phonecontrol.ScreenshotCapture
import com.unoone.agent.screenshot.ScreenshotPermissionActivity
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes tool calls that the orchestrator has validated and classified.
 * Responsible for the actual side effects: opening apps, creating notes, etc.
 */
class ActionExecutor(
    private val context: Context,
    private val noteDao: NoteDao,
    private val skillDao: SkillDao,
    private val memoryDao: MemoryDao,
    private val actionLogDao: ActionLogDao,
    private val phoneControl: PhoneControl,
    private val calendarControl: CalendarControl,
    private val ocrControl: OcrControl,
    private val accessibilityControl: AccessibilityControl,
    private val agentRouter: AgentRouter
) : IActionExecutor {

    private val dataExporter = DataExporter(context, noteDao, skillDao, memoryDao, actionLogDao)

    override suspend fun executeTool(toolCall: ToolCall): Result<String> {
        return try {
            when (toolCall.tool) {
                "create_note" -> {
                    val title = toolCall.args["title"]?.jsonPrimitive?.content
                        ?: toolCall.args["content"]?.jsonPrimitive?.content?.take(30) ?: "Note"
                    val content = toolCall.args["content"]?.jsonPrimitive?.content ?: ""
                    val tags = toolCall.args["tags"]?.jsonPrimitive?.content ?: ""
                    noteDao.insert(NoteEntity(title = title, content = content, tags = tags))
                    Result.Success("Note '$title' saved.")
                }
                "create_skill" -> {
                    val name = toolCall.args["name"]?.jsonPrimitive?.content ?: "Custom Skill"
                    // "steps" may arrive as a pipe-delimited string (rule-based parser) OR as a JSON
                    // array of strings (LLM tool-calling, per UnoOneToolSet). Handle both so the
                    // LLM path doesn't throw on .jsonPrimitive-of-a-JsonArray and silently fail.
                    val stepsList: List<String> = when (val stepsEl = toolCall.args["steps"]) {
                        null -> emptyList()
                        is JsonArray -> stepsEl.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        else -> stepsEl.jsonPrimitive.content.split("|").filter { it.isNotBlank() }
                    }
                    val module = _skillsModule
                        ?: return Result.Error("Skills module not available")
                    try {
                        module.saveSkill(name, listOf(name), stepsList)
                        Result.Success("Skill '$name' deployed with ${stepsList.size} step(s).")
                    } catch (e: Exception) {
                        Result.Error("Failed to save skill: ${e.message}")
                    }
                }
                "search_notes" -> {
                    val query = toolCall.args["query"]?.jsonPrimitive?.content ?: ""
                    val results = noteDao.searchOnce(query)
                    if (results.isEmpty()) Result.Success("No notes match '$query'.")
                    else Result.Success(results.joinToString(separator = " | ") {
                        "'${it.title}'${if (it.content.isNotBlank()) ": ${it.content.take(80)}" else ""}"
                    })
                }
                "summarize_text" -> {
                    val text = toolCall.args["text"]?.jsonPrimitive?.content ?: ""
                    if (text.isBlank()) Result.Error("Nothing to summarize")
                    else Result.Success(TextSummarizer.summarize(text))
                }
                "speak_response" -> {
                    val text = toolCall.args["text"]?.jsonPrimitive?.content ?: ""
                    _speak?.invoke(text)
                    Result.Success(text)
                }
                "delete_notes" -> {
                    val query = toolCall.args["query"]?.jsonPrimitive?.content ?: ""
                    if (query.isBlank()) Result.Error("delete_notes requires a query")
                    else {
                        val count = noteDao.deleteByQuery(query)
                        Result.Success("Deleted $count note(s) matching '$query'.")
                    }
                }
                "delete_all_notes" -> {
                    val count = noteDao.deleteAll()
                    Result.Success("Deleted all notes ($count).")
                }
                "export_data" -> {
                    dataExporter.export().map { "Exported to $it" }
                }
                "draft_email" -> {
                    val to = toolCall.args["to"]?.jsonPrimitive?.content ?: ""
                    val sub = toolCall.args["subject"]?.jsonPrimitive?.content ?: "Update"
                    val body = toolCall.args["body"]?.jsonPrimitive?.content ?: ""
                    phoneControl.draftEmail(to, sub, body)
                        .map { "Email draft opened. Please review and press send." }
                }
                "send_whatsapp" -> {
                    val number = toolCall.args["number"]?.jsonPrimitive?.content ?: ""
                    val msg = toolCall.args["message"]?.jsonPrimitive?.content ?: ""
                    phoneControl.sendWhatsAppMessage(number, msg)
                        .map { "WhatsApp draft opened. Please review and press send." }
                }
                "check_calendar" -> {
                    val now = System.currentTimeMillis()
                    val eventsResult = calendarControl.getEvents(now, now + 86400000)
                    if (eventsResult is Result.Success) {
                        val events = eventsResult.data
                        if (events.isEmpty()) Result.Success("Calendar clear today.")
                        else Result.Success("You have ${events.size} events.")
                    } else Result.Error("Calendar access failed.")
                }
                "open_calendar_insert" -> {
                    val title = toolCall.args["title"]?.jsonPrimitive?.content ?: "Untitled event"
                    val now = System.currentTimeMillis()
                    val start = parseTimeMs(toolCall.args["start_time"]?.jsonPrimitive?.content) ?: now
                    val end = parseTimeMs(toolCall.args["end_time"]?.jsonPrimitive?.content) ?: (start + 3_600_000L)
                    phoneControl.openCalendarInsert(title, start, end)
                        .map { "Calendar insert opened for '$title'." }
                }
                "open_app" -> {
                    val appName = toolCall.args["app_name"]?.jsonPrimitive?.content ?: ""
                    val pkg = toolCall.args["package_name"]?.jsonPrimitive?.content
                        ?: PackageResolver.resolveAppName(appName)
                        ?: return Result.Error("Could not resolve app '$appName'. Provide package_name.")
                    phoneControl.openApp(pkg).map { "Opened $appName." }
                }
                "open_url" -> {
                    val url = toolCall.args["url"]?.jsonPrimitive?.content ?: ""
                    if (url.isBlank()) Result.Error("open_url requires a url")
                    else phoneControl.openUrl(url).map { "Opened $url." }
                }
                "open_dialer" -> {
                    val number = toolCall.args["number"]?.jsonPrimitive?.content
                    phoneControl.openDialer(number).map { "Dialer opened." }
                }
                "share_text" -> {
                    val text = toolCall.args["text"]?.jsonPrimitive?.content ?: ""
                    if (text.isBlank()) Result.Error("share_text requires text")
                    else phoneControl.shareText(text).map { "Share sheet opened." }
                }
                "open_chrome" -> phoneControl.openChrome().map { "Chrome opened." }
                "open_camera" -> phoneControl.openCamera().map { "Camera active." }
                "system_control" -> executeSystemAction(toolCall)
                "ocr_screen" -> readScreenWithOcr()
                "read_screen" -> readScreenWithAccessibility()
                "detect_objects" -> {
                    _setBlindAidActive?.invoke(true)
                    Result.Success("Blind Aid activated.")
                }
                "deactivate_blind_aid" -> {
                    _setBlindAidActive?.invoke(false)
                    Result.Success("Blind Aid deactivated.")
                }
                // "compound" is expanded into ordered sub-calls by AgentOrchestrator and never
                // reaches executeTool; fall through to the plugin router for anything unrecognized.
                else -> agentRouter.route(toolCall)
            }
        } catch (e: Exception) {
            Result.Error("Action failed: ${e.message}")
        }
    }

    override fun getRequiredPermissionsForTool(tool: String): List<String> {
        // Single source of truth: ToolPermissionRegistry. This previously duplicated (and
        // mis-mapped) the table — see SafetyPipeline for the full requirement check.
        return ToolPermissionRegistry.runtimePermissionsFor(tool)
    }

    // Injected callbacks — set by Orchestrator to avoid circular dependencies
    var _skillsModule: com.unoone.agent.skills.SkillsModule? = null
    var _setBlindAidActive: ((Boolean) -> Unit)? = null
    /** Speak text via the shared VoiceModule (TTS). Lets speak_response force audio in any input mode. */
    var _speak: ((String) -> Unit)? = null

    /**
     * Parses an ISO-8601 time string (Instant / offset / local) to epoch millis, or null if absent
     * or unparseable. Used by open_calendar_insert to honour model-provided start/end times.
     */
    private fun parseTimeMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.ZonedDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(iso)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * read_screen: reads on-screen text via the Accessibility tree only. The pre-execution gate
     * already required Accessibility; we do NOT prompt for MediaProjection mid-execution — that
     * would request access the permission registry never declared for this tool.
     */
    private suspend fun readScreenWithAccessibility(): Result<String> {
        val accResult = accessibilityControl.captureScreenText()
        return when (accResult) {
            is Result.Success -> if (accResult.data.isNotBlank()) {
                Result.Success(accResult.data)
            } else {
                Result.Error("No readable text on screen")
            }
            is Result.Error -> Result.Error(accResult.message)
        }
    }

    /**
     * ocr_screen: runs OCR on a MediaProjection screenshot. The pre-execution gate already required
     * MediaProjection; we go straight to OCR rather than returning the accessibility tree (which
     * would defeat the purpose of a dedicated OCR tool).
     */
    private suspend fun readScreenWithOcr(): Result<String> {
        if (!ScreenshotCapture.hasPermission()) {
            // Should not happen — the gate checks this before execute — but be defensive.
            return Result.Error("Screenshot permission not granted. Grant it in Settings.")
        }
        return when (val ocrResult = ocrControl.recognizeScreen()) {
            is Result.Success -> if (ocrResult.data.isNotBlank()) {
                Result.Success(ocrResult.data)
            } else {
                Result.Error("No text found on screen")
            }
            is Result.Error -> Result.Error(ocrResult.message)
        }
    }

    private suspend fun executeSystemAction(toolCall: ToolCall): Result<String> {
        val action = toolCall.args["action"]?.jsonPrimitive?.content ?: ""
        val target = toolCall.args["target"]?.jsonPrimitive?.content ?: ""
        return when (action) {
            "click" -> accessibilityControl.clickText(target).map { "Clicked $target" }
            "type" -> accessibilityControl.typeText(target).map { "Typed text" }
            "fill" -> {
                val value = toolCall.args["value"]?.jsonPrimitive?.content ?: ""
                accessibilityControl.fillField(target, value).map { "Filled $target" }
            }
            "scroll_down" -> accessibilityControl.scrollDown().map { "Scrolled down" }
            "scroll_up" -> accessibilityControl.scrollUp().map { "Scrolled up" }
            "swipe" -> accessibilityControl.swipe(target).map { "Swiped $target" }
            "long_press" -> {
                val x = target.toFloatOrNull()
                val y = toolCall.args["y"]?.jsonPrimitive?.content?.toFloatOrNull()
                if (x != null && y != null) {
                    accessibilityControl.longPress(x, y).map { "Long pressed at ($x, $y)" }
                } else if (target.isNotBlank()) {
                    accessibilityControl.longPressNodeWithText(target).map { "Long pressed '$target'" }
                } else {
                    Result.Error("Long press requires either coordinates or a target text label")
                }
            }
            "go_back" -> accessibilityControl.goBack().map { "Went back" }
            "go_home" -> accessibilityControl.goHome().map { "Went home" }
            "open_notifications" -> accessibilityControl.openNotifications().map { "Opened notifications" }
            "open_recents" -> accessibilityControl.openRecents().map { "Opened recents" }
            "find_and_click" -> accessibilityControl.findAndClick(target).map { "Found and clicked $target" }
            else -> Result.Error("Unknown system action: $action")
        }
    }

    private fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }
}