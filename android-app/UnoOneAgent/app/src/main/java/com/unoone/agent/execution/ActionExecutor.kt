package com.unoone.agent.execution

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.interfaces.IActionExecutor
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import com.unoone.agent.phonecontrol.CalendarControl
import com.unoone.agent.phonecontrol.OcrControl
import com.unoone.agent.phonecontrol.PhoneControl
import com.unoone.agent.phonecontrol.ScreenshotCapture
import com.unoone.agent.screenshot.ScreenshotPermissionActivity
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.NoteEntity
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
    private val phoneControl: PhoneControl,
    private val calendarControl: CalendarControl,
    private val ocrControl: OcrControl,
    private val accessibilityControl: AccessibilityControl,
    private val agentRouter: AgentRouter
) : IActionExecutor {

    override suspend fun executeTool(toolCall: ToolCall): Result<String> {
        return try {
            when (toolCall.tool) {
                "create_note" -> {
                    val content = toolCall.args["content"]?.jsonPrimitive?.content ?: ""
                    noteDao.insert(NoteEntity(title = content.take(30), content = content))
                    Result.Success("Note saved.")
                }
                "create_skill" -> {
                    val name = toolCall.args["name"]?.jsonPrimitive?.content ?: "Custom Skill"
                    val stepsStr = toolCall.args["steps"]?.jsonPrimitive?.content ?: ""
                    val stepsList = stepsStr.split("|")
                    // SkillsModule is injected via setter to avoid circular dependency
                    _skillsModule?.saveSkill(name, listOf(name), stepsList)
                        ?: Result.Error("Skills module not available")
                    Result.Success("Skill '$name' deployed.")
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
                "open_chrome" -> phoneControl.openChrome().map { "Chrome opened." }
                "open_camera" -> phoneControl.openCamera().map { "Camera active." }
                "system_control" -> executeSystemAction(toolCall)
                "ocr_screen", "read_screen" -> readScreenWithOcrFallback()
                "detect_objects" -> {
                    _setBlindAidActive?.invoke(true)
                    Result.Success("Blind Aid activated.")
                }
                "deactivate_blind_id" -> {
                    _setBlindAidActive?.invoke(false)
                    Result.Success("Blind Aid deactivated.")
                }
                "compound" -> {
                    // Fallback: execute first part only
                    executeCompoundPart(toolCall, "first")
                }
                else -> agentRouter.route(toolCall)
            }
        } catch (e: Exception) {
            Result.Error("Action failed: ${e.message}")
        }
    }

    override fun getRequiredPermissionsForTool(tool: String): List<String> {
        return when (tool) {
            "create_note" -> emptyList()
            "draft_email" -> emptyList()
            "send_whatsapp" -> emptyList()
            "check_calendar" -> listOf(Manifest.permission.READ_CALENDAR)
            "open_calendar_insert" -> listOf(Manifest.permission.WRITE_CALENDAR)
            "open_camera" -> listOf(Manifest.permission.CAMERA)
            "ocr_screen", "read_screen" -> listOf(Manifest.permission.SYSTEM_ALERT_WINDOW)
            "system_control" -> listOf(Manifest.permission.SYSTEM_ALERT_WINDOW)
            "voice_recording" -> listOf(Manifest.permission.RECORD_AUDIO)
            "detect_objects" -> listOf(Manifest.permission.CAMERA)
            "deactivate_blind_aid" -> emptyList()
            else -> emptyList()
        }
    }

    // Injected callbacks — set by Orchestrator to avoid circular dependencies
    var _skillsModule: com.unoone.agent.skills.SkillsModule? = null
    var _setBlindAidActive: ((Boolean) -> Unit)? = null

    private suspend fun readScreenWithOcrFallback(): Result<String> {
        // 1. Try the lightweight accessibility tree first.
        val accResult = accessibilityControl.captureScreenText()
        if (accResult is Result.Success && accResult.data.isNotBlank()) {
            return Result.Success(accResult.data)
        }

        // 2. Fall back to MediaProjection screenshot OCR if permission is available.
        if (!ScreenshotCapture.hasPermission()) {
            Logger.i("ActionExecutor: accessibility text empty; requesting MediaProjection")
            ScreenshotPermissionActivity.launch(context)
            return Result.Error("Screen text not available. Opening screenshot permission.")
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

    private suspend fun executeCompoundPart(compound: ToolCall, which: String): Result<String> {
        val toolName = compound.args["${which}_tool"]?.jsonPrimitive?.content
            ?: return Result.Error("Missing $which tool in compound")
        val argsJson = compound.args["${which}_args"]?.jsonPrimitive?.content ?: "{}"
        val args = try {
            kotlinx.serialization.json.Json.decodeFromString<JsonObject>(argsJson)
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        return executeTool(ToolCall(toolName, args))
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