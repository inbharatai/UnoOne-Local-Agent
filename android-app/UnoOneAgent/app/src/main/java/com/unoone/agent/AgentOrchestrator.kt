package com.unoone.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.Logger
import com.unoone.agent.localbrain.LocalBrain
import com.unoone.agent.localbrain.RuleBasedParser
import com.unoone.agent.memory.MemoryModule
import com.unoone.agent.phonecontrol.CalendarControl
import com.unoone.agent.phonecontrol.OcrControl
import com.unoone.agent.phonecontrol.PhoneControl
import com.unoone.agent.safetyguard.SafetyGuard
import com.unoone.agent.skills.SkillsModule
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.ActionLogEntity
import com.unoone.agent.storage.entity.NoteEntity
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentOrchestrator(
    private val context: Context,
    private val noteDao: NoteDao,
    private val actionLogDao: ActionLogDao,
    private val memoryDao: MemoryDao,
    private val skillDao: SkillDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared VoiceModule — set externally by the Application/ViewModel to avoid duplicate instances
    var voiceModule: VoiceModule = VoiceModule(context)
        private set

    private val localBrain = LocalBrain()
    private val agentRouter = AgentRouter()
    private val safetyGuard = SafetyGuard()
    private val phoneControl = PhoneControl(context)
    private val calendarControl = CalendarControl(context)
    private val ocrControl = OcrControl(context)
    private val accessibilityControl = AccessibilityControl()
    private val memoryModule = MemoryModule(memoryDao)
    val skillsModule = SkillsModule(skillDao)

    private val _timelineSteps = MutableStateFlow<List<TimelineStep>>(emptyList())
    val timelineSteps: StateFlow<List<TimelineStep>> = _timelineSteps.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isBlindAidActive = MutableStateFlow(false)
    val isBlindAidActive: StateFlow<Boolean> = _isBlindAidActive.asStateFlow()

    var onPermissionRequired: ((List<String>) -> Unit)? = null
    var onConfirmationRequired: ((String, (Boolean) -> Unit) -> Unit)? = null

    // Pending command for re-execution after permission grant
    private var pendingCommand: String? = null
    private var pendingInputType: InputType? = null

    /**
     * Injects the shared VoiceModule from the Application/ViewModel layer.
     * Called once at startup to eliminate the dual-instance problem.
     */
    fun setVoiceModule(shared: VoiceModule) {
        voiceModule = shared
    }

    fun setBlindAidActive(active: Boolean) {
        _isBlindAidActive.value = active
        if (active) {
            voiceModule.speak("Blind Aid activated. Scanning for obstacles ahead.")
            // If activated from background (no Activity in foreground), launch MainActivity
            // so the CameraX preview can bind to a lifecycle and start scanning
            bringAppToForegroundIfNeeded()
        } else {
            voiceModule.speak("Blind Aid deactivated.")
        }
    }

    /**
     * When blind aid is activated from a background path (VoiceService broadcast, FloatingAgent),
     * the CameraX preview in AgentScreen needs an active lifecycle to bind to.
     * This brings the app to the foreground so the Compose UI can start the camera.
     */
    private fun bringAppToForegroundIfNeeded() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("activate_blind_aid", true)
            }
            context.startActivity(intent)
            Logger.i("Orchestrator: Launched MainActivity for blind-aid camera binding")
        } catch (e: Exception) {
            Logger.e("Orchestrator: Failed to launch MainActivity for blind-aid", e)
        }
    }

    suspend fun processCommand(text: String, inputType: InputType = InputType.TEXT) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        _timelineSteps.value = emptyList()

        val startTime = System.currentTimeMillis()
        val log = ActionLogEntity(inputText = text, inputType = inputType.name.lowercase())

        try {
            addStep(AgentStatus.UNDERSTANDING, "Understanding Command", text)

            // Step 1: Check if this triggers a custom Skill
            val skill = skillsModule.findSkillByTrigger(text)
            if (skill != null) {
                addStep(AgentStatus.TOOL_SELECTED, "Executing Skill", skill.name)
                val steps = skillsModule.getSkillSteps(skill)
                for (step in steps) {
                    addStep(AgentStatus.EXECUTING, "Skill Step", step)
                    val toolCall = parseCommand(step)
                    if (toolCall != null) {
                        executeTool(toolCall)
                    }
                }
                addStep(AgentStatus.DONE, "Skill Complete", "Sequence finished successfully")
                saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "success", modelLatencyMs = System.currentTimeMillis() - startTime))
                _isProcessing.value = false
                return
            }

            // Step 2: Planning / Intent Extraction
            val toolCall = parseCommand(text)
            if (toolCall == null) {
                addStep(AgentStatus.FAILED, "Accuracy Alert", "Intent not clear. Please rephrase.")
                saveLog(log.copy(status = "failed", errorMessage = "Extraction failed"))
                _isProcessing.value = false
                return
            }

            // Step 2b: Expand compound commands into sequential execution
            if (toolCall.tool == "compound") {
                addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Compound: executing both parts")
                val firstResult = executeCompoundPart(toolCall, "first")
                val secondResult = executeCompoundPart(toolCall, "second")
                val combined: Result<String> = when {
                    firstResult is Result.Error && secondResult is Result.Error ->
                        Result.Error("Both commands failed: ${firstResult.message}; ${secondResult.message}")
                    firstResult is Result.Error -> secondResult
                    secondResult is Result.Error -> firstResult
                    firstResult is Result.Success && secondResult is Result.Success ->
                        Result.Success("${firstResult.data}; ${secondResult.data}")
                    else -> Result.Success("Done")
                }
                if (combined is Result.Error) {
                    addStep(AgentStatus.FAILED, "Execution Error", combined.message)
                } else if (combined is Result.Success) {
                    addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Both commands complete")
                    if (inputType == InputType.VOICE) {
                        addStep(AgentStatus.SPEAKING, "Response", combined.data)
                        voiceModule.speak(combined.data)
                    }
                }
                saveLog(log.copy(selectedTool = "compound", status = if (combined is Result.Error) "failed" else "success"))
                _isProcessing.value = false
                return
            }

            addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Action: ${toolCall.tool}")

            // Step 3: Dynamic Permission Check
            val missingPermissions = getRequiredPermissionsForTool(toolCall.tool).filter { perm ->
                if (perm == Manifest.permission.SYSTEM_ALERT_WINDOW) {
                    !Settings.canDrawOverlays(context)
                } else {
                    PackageManager.PERMISSION_GRANTED != ContextCompat.checkSelfPermission(context, perm)
                }
            }

            if (missingPermissions.isNotEmpty()) {
                addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs permissions")
                pendingCommand = text
                pendingInputType = inputType
                onPermissionRequired?.invoke(missingPermissions)
                _isProcessing.value = false
                return
            }

            // Step 4: Risk Classification & Confirmation
            val riskLevel = safetyGuard.classify(toolCall.tool)
            addStep(AgentStatus.SAFETY_CHECK, "Safety Filter", "Risk: ${riskLevel.name}")

            if (riskLevel == RiskLevel.BLOCK) {
                addStep(AgentStatus.FAILED, "Security Block", "Action blocked for security.")
                saveLog(log.copy(selectedTool = toolCall.tool, status = "blocked"))
                _isProcessing.value = false
                return
            }

            if (riskLevel == RiskLevel.CONFIRM || riskLevel == RiskLevel.STRONG_CONFIRM) {
                val confirmationMessage = when (riskLevel) {
                    RiskLevel.CONFIRM -> "Confirm: Execute ${toolCall.tool}?"
                    RiskLevel.STRONG_CONFIRM -> "SECURITY CHECK: This action (${toolCall.tool}) is sensitive. Confirm?"
                    else -> "Confirm?"
                }
                addStep(AgentStatus.SAFETY_CHECK, "Confirmation Required", confirmationMessage)
                val confirmed = awaitConfirmation(confirmationMessage)
                if (!confirmed) {
                    addStep(AgentStatus.FAILED, "Cancelled", "User declined confirmation")
                    saveLog(log.copy(selectedTool = toolCall.tool, status = "cancelled"))
                    _isProcessing.value = false
                    return
                }
            }

            // Step 5: Execution
            addStep(AgentStatus.EXECUTING, "Agent Active", "Executing ${toolCall.tool}...")
            val result = executeTool(toolCall)

            if (result is Result.Error) {
                addStep(AgentStatus.FAILED, "Execution Error", result.message)
                saveLog(log.copy(selectedTool = toolCall.tool, status = "failed", errorMessage = result.message))
                _isProcessing.value = false
                return
            }

            // Step 6: Feedback & Verification
            addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Task complete")
            val responseText = if (result is Result.Success) result.data.toString() else "Action completed."

            if (inputType == InputType.VOICE) {
                addStep(AgentStatus.SPEAKING, "Response", responseText)
                voiceModule.speak(responseText)
            } else {
                addStep(AgentStatus.DONE, "Done", responseText)
            }

            val endTime = System.currentTimeMillis()
            saveLog(log.copy(
                selectedTool = toolCall.tool,
                toolArgsJson = toolCall.args.toString(),
                status = "success",
                modelLatencyMs = endTime - startTime
            ))
        } catch (e: Exception) {
            Logger.e("Master Orchestrator Exception", e)
            addStep(AgentStatus.FAILED, "System Error", e.localizedMessage ?: "Error")
        } finally {
            _isProcessing.value = false
        }
    }

    fun clearPendingAndReExecute() {
        val cmd = pendingCommand
        val type = pendingInputType
        pendingCommand = null
        pendingInputType = null
        if (cmd != null && type != null) {
            scope.launch { processCommand(cmd, type) }
        }
    }

    private suspend fun awaitConfirmation(message: String): Boolean {
        return if (onConfirmationRequired != null) {
            suspendCancellableCoroutine { cont ->
                onConfirmationRequired?.invoke(message) { result ->
                    cont.resumeWith(kotlin.Result.success(result))
                } ?: cont.resumeWith(kotlin.Result.success(true))
            }
        } else {
            true
        }
    }

    private fun getRequiredPermissionsForTool(tool: String): List<String> {
        return when (tool) {
            "create_note" -> emptyList()
            "draft_email" -> listOf(Manifest.permission.READ_CONTACTS)
            "send_whatsapp" -> listOf(Manifest.permission.READ_CONTACTS)
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

    private fun parseCommand(text: String): ToolCall? {
        val ruleResult = RuleBasedParser.parse(text)
        if (ruleResult != null) return ruleResult

        if (localBrain.isModelLoaded()) {
            val inferenceResult = localBrain.runInference(text)
            if (inferenceResult is Result.Success) return inferenceResult.data
        }
        return null
    }

    private suspend fun executeTool(toolCall: ToolCall): Result<String> {
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
                    skillsModule.saveSkill(name, listOf(name), stepsList)
                    Result.Success("Skill '$name' deployed.")
                }
                "draft_email" -> {
                    val to = toolCall.args["to"]?.jsonPrimitive?.content ?: ""
                    val sub = toolCall.args["subject"]?.jsonPrimitive?.content ?: "Update"
                    val body = toolCall.args["body"]?.jsonPrimitive?.content ?: ""
                    phoneControl.draftEmail(to, sub, body).map { "Email drafted." }
                }
                "send_whatsapp" -> {
                    val number = toolCall.args["number"]?.jsonPrimitive?.content ?: ""
                    val msg = toolCall.args["message"]?.jsonPrimitive?.content ?: ""
                    phoneControl.sendWhatsAppMessage(number, msg).map { "WhatsApp prepared." }
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
                "system_control" -> {
                    val action = toolCall.args["action"]?.jsonPrimitive?.content ?: ""
                    val target = toolCall.args["target"]?.jsonPrimitive?.content ?: ""
                    when (action) {
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
                            // If target is a text label (not numeric coords), find the node and long-press it
                            val x = target.toFloatOrNull()
                            val y = toolCall.args["y"]?.jsonPrimitive?.content?.toFloatOrNull()
                            if (x != null && y != null) {
                                accessibilityControl.longPress(x, y).map { "Long pressed at ($x, $y)" }
                            } else if (target.isNotBlank()) {
                                // Text-based long press: find the node and long-press it
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
                "ocr_screen", "read_screen" -> accessibilityControl.captureScreenText()
                "detect_objects" -> {
                    setBlindAidActive(true)
                    Result.Success("Blind Aid activated.")
                }
                "deactivate_blind_aid" -> {
                    setBlindAidActive(false)
                    Result.Success("Blind Aid deactivated.")
                }
                "compound" -> {
                    // This should be handled in processCommand before reaching executeTool,
                    // but handle it here as a fallback: execute first part only
                    val firstResult = executeCompoundPart(toolCall, "first")
                    firstResult
                }
                else -> agentRouter.route(toolCall)
            }
        } catch (e: Exception) {
            Result.Error("Action failed: ${e.message}")
        }
    }

    /**
     * Executes one half of a compound tool call.
     */
    private suspend fun executeCompoundPart(compound: ToolCall, which: String): Result<String> {
        val toolName = compound.args["${which}_tool"]?.jsonPrimitive?.content ?: return Result.Error("Missing $which tool in compound")
        val argsJson = compound.args["${which}_args"]?.jsonPrimitive?.content ?: "{}"
        // Reconstruct the ToolCall from the serialized args
        val args = try {
            kotlinx.serialization.json.Json.decodeFromString<JsonObject>(argsJson)
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        return executeTool(ToolCall(toolName, args))
    }

    private fun addStep(status: AgentStatus, label: String, detail: String = "") {
        val current = _timelineSteps.value.toMutableList()
        current.add(TimelineStep(status, label, detail))
        _timelineSteps.value = current
    }

    private suspend fun saveLog(log: ActionLogEntity) {
        try { actionLogDao.insert(log) } catch (e: Exception) { Logger.e("Log error", e) }
    }

    private fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> {
        return when (this) {
            is Result.Success -> Result.Success(transform(data))
            is Result.Error -> this
        }
    }
}