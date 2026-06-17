package com.unoone.agent

import android.content.Context
import android.content.Intent
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.model.onError
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.util.CallbackMulticast
import com.unoone.agent.core.util.ConfirmationListener
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.util.PermissionListener
import com.unoone.agent.execution.ActionExecutor
import com.unoone.agent.parsing.CommandParser
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.safety.SafetyPipeline
import com.unoone.agent.skills.SkillsModule
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.ActionLogEntity
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central orchestrator that coordinates command parsing, safety checks, and action execution.
 * Delegates to extracted components:
 * - [CommandParser] for input → ToolCall parsing
 * - [SafetyPipeline] for permission checks and risk classification
 * - [ActionExecutor] for side-effect execution
 */
class AgentOrchestrator(
    private val context: Context,
    private val noteDao: NoteDao,
    private val actionLogDao: ActionLogDao,
    private val memoryDao: MemoryDao,
    private val skillDao: SkillDao
) {
    // 0C-12: Use Dispatchers.Default for CPU-bound orchestration work.
    // DB writes use Dispatchers.IO via withContext. StateFlow.value setter is thread-safe.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Shared VoiceModule — set externally by the Application/ViewModel to avoid duplicate instances.
    lateinit var voiceModule: VoiceModule
        private set

    // Extracted components — Phase 1A: God object split
    private val memoryModule = com.unoone.agent.memory.MemoryModule(memoryDao)
    private val commandParser = CommandParser(
        accessibilityControl = com.unoone.agent.accessibilitycontrol.AccessibilityControl(),
        memoryModule = memoryModule
    )
    private val actionExecutor = ActionExecutor(
        context = context,
        noteDao = noteDao,
        skillDao = skillDao,
        phoneControl = com.unoone.agent.phonecontrol.PhoneControl(context),
        calendarControl = com.unoone.agent.phonecontrol.CalendarControl(context),
        ocrControl = com.unoone.agent.phonecontrol.OcrControl(context),
        accessibilityControl = com.unoone.agent.accessibilitycontrol.AccessibilityControl(),
        agentRouter = com.unoone.agent.agentrouter.AgentRouter()
    )
    private val safetyPipeline = SafetyPipeline(
        context = context,
        safetyGuard = com.unoone.agent.safetyguard.SafetyGuard()
    )

    val skillsModule = SkillsModule(skillDao)

    // Wire ActionExecutor callbacks to orchestrator state
    init {
        actionExecutor._skillsModule = skillsModule
        actionExecutor._setBlindAidActive = { active -> setBlindAidActive(active) }
    }

    private val _timelineSteps = MutableStateFlow<List<TimelineStep>>(emptyList())
    val timelineSteps: StateFlow<List<TimelineStep>> = _timelineSteps.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    private val processingLock = AtomicBoolean(false)

    private val _isBlindAidActive = MutableStateFlow(false)
    val isBlindAidActive: StateFlow<Boolean> = _isBlindAidActive.asStateFlow()

    var onPermissionRequired: ((List<String>) -> Unit)? = null
    var onConfirmationRequired: ((String, (Boolean) -> Unit) -> Unit)? = null

    // Thread-safe multicast callbacks — both MainActivity and FloatingAgentService
    // can register simultaneously without overwriting each other.
    val onPermissionRequiredMulticast = CallbackMulticast<PermissionListener>()
    val onConfirmationRequiredMulticast = CallbackMulticast<ConfirmationListener>()

    // Pending command for re-execution after permission grant (thread-safe)
    private val pendingCommand = AtomicReference<String?>(null)
    private val pendingInputType = AtomicReference<InputType?>(null)

    /**
     * Injects the shared VoiceModule from the Application/ViewModel layer.
     * Called once at startup to eliminate the dual-instance problem.
     */
    fun setVoiceModule(shared: VoiceModule) {
        voiceModule = shared
    }

    /**
     * Loads a Gemma 4 `.litertlm` model into the command parser's LiteRT-LM brain.
     * Should be called from a coroutine (engine init is slow).
     */
    suspend fun loadLlmModel(modelPath: String): com.unoone.agent.core.model.Result<Unit> {
        return commandParser.loadModel(modelPath)
    }

    fun setBlindAidActive(active: Boolean) {
        _isBlindAidActive.value = active
        if (active) {
            voiceModule.speak("Blind Aid activated. Scanning for obstacles ahead.")
                .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Blind aid speak failed: $msg") }
            bringAppToForegroundIfNeeded()
        } else {
            voiceModule.speak("Blind Aid deactivated.")
                .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Blind aid speak failed: $msg") }
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
            }
            context.startActivity(intent)
            Logger.i("Orchestrator: Launched MainActivity for blind-aid camera binding")
        } catch (e: Exception) {
            Logger.e("Orchestrator: Failed to launch MainActivity for blind-aid", e)
        }
    }

    suspend fun processCommand(text: String, inputType: InputType = InputType.TEXT) {
        // Atomic check-and-set to prevent concurrent command execution
        if (!processingLock.compareAndSet(false, true)) return
        _isProcessing.value = true
        _timelineSteps.value = emptyList()

        // SECURITY: Sanitize user input before processing
        val sanitizedText = InputSanitizer.sanitize(text)
        if (sanitizedText.isBlank()) {
            addStep(AgentStatus.FAILED, "Empty Input", "No command detected after sanitization.")
            releaseProcessingLock()
            return
        }

        val startTime = System.currentTimeMillis()
        val log = ActionLogEntity(inputText = sanitizedText, inputType = inputType.name.lowercase())

        try {
            addStep(AgentStatus.UNDERSTANDING, "Understanding Command", sanitizedText)

            // Step 1: Check if this triggers a custom Skill
            val skill = skillsModule.findSkillByTrigger(sanitizedText)
            if (skill != null) {
                addStep(AgentStatus.TOOL_SELECTED, "Executing Skill", skill.name)
                val steps = skillsModule.getSkillSteps(skill)
                for (step in steps) {
                    addStep(AgentStatus.EXECUTING, "Skill Step", step)
                    val toolCall = commandParser.parse(step)
                    if (toolCall != null) {
                        actionExecutor.executeTool(toolCall)
                    }
                }
                addStep(AgentStatus.DONE, "Skill Complete", "Sequence finished successfully")
                saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "success", modelLatencyMs = System.currentTimeMillis() - startTime))
                releaseProcessingLock()
                return
            }

            // Step 2: Planning / Intent Extraction — delegate to CommandParser
            val toolCall = commandParser.parseAsync(sanitizedText)
            if (toolCall == null) {
                addStep(AgentStatus.FAILED, "Accuracy Alert", "Intent not clear. Please rephrase.")
                saveLog(log.copy(status = "failed", errorMessage = "Extraction failed"))
                releaseProcessingLock()
                return
            }

            // Step 2b: Expand compound commands — run full permission + safety checks on each part
            if (toolCall.tool == "compound") {
                handleCompoundCommand(toolCall, sanitizedText, inputType, log, startTime)
                releaseProcessingLock()
                return
            }

            addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Action: ${toolCall.tool}")

            // Step 3: Dynamic Permission Check — delegate to SafetyPipeline
            val missingPermissions = safetyPipeline.checkPermissionsForTool(toolCall.tool)
            if (missingPermissions.isNotEmpty()) {
                addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs permissions")
                pendingCommand.set(text)
                pendingInputType.set(inputType)
                onPermissionRequired?.invoke(missingPermissions)
                onPermissionRequiredMulticast.invokeAll { it(missingPermissions) }
                releaseProcessingLock()
                return
            }

            // Step 4: Risk Classification & Confirmation — delegate to SafetyPipeline
            val riskLevel = safetyPipeline.classifyRisk(toolCall.tool, sanitizedText)
            addStep(AgentStatus.SAFETY_CHECK, "Safety Filter", "Risk: ${riskLevel.name}")

            if (safetyPipeline.isBlocked(riskLevel)) {
                addStep(AgentStatus.FAILED, "Security Block", "Action blocked for security.")
                AuditLogger.log(toolCall.tool, riskLevel, "blocked", sanitizedText)
                saveLog(log.copy(selectedTool = toolCall.tool, status = "blocked"))
                releaseProcessingLock()
                return
            }

            if (safetyPipeline.requiresConfirmation(riskLevel)) {
                val confirmationMessage = safetyPipeline.confirmationMessage(toolCall.tool, riskLevel)
                addStep(AgentStatus.SAFETY_CHECK, "Confirmation Required", confirmationMessage)
                val confirmed = awaitConfirmation(confirmationMessage)
                if (!confirmed) {
                    addStep(AgentStatus.FAILED, "Cancelled", "User declined confirmation")
                    AuditLogger.log(toolCall.tool, riskLevel, "cancelled", sanitizedText)
                    saveLog(log.copy(selectedTool = toolCall.tool, status = "cancelled"))
                    releaseProcessingLock()
                    return
                }
            }

            // Step 5: Execution — delegate to ActionExecutor
            addStep(AgentStatus.EXECUTING, "Agent Active", "Executing ${toolCall.tool}...")
            val result = actionExecutor.executeTool(toolCall)

            if (result is Result.Error) {
                addStep(AgentStatus.FAILED, "Execution Error", result.message)
                saveLog(log.copy(selectedTool = toolCall.tool, status = "failed", errorMessage = result.message))
                releaseProcessingLock()
                return
            }

            // Step 6: Feedback & Verification
            addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Task complete")
            val responseText = if (result is Result.Success) result.data.toString() else "Action completed."

            if (inputType == InputType.VOICE) {
                addStep(AgentStatus.SPEAKING, "Response", responseText)
                voiceModule.speak(responseText)
                    .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Response speak failed: $msg") }
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
            releaseProcessingLock()
            processingLock.set(false)
        }
    }

    fun clearPendingAndReExecute() {
        val cmd = pendingCommand.getAndSet(null)
        val type = pendingInputType.getAndSet(null)
        if (cmd != null && type != null) {
            scope.launch { processCommand(cmd, type) }
        }
    }

    /**
     * Handles compound tool calls with per-part permission and safety checks.
     * Kept in Orchestrator because it coordinates confirmation flow across multiple parts.
     */
    private suspend fun handleCompoundCommand(
        toolCall: ToolCall,
        sanitizedText: String,
        inputType: InputType,
        log: ActionLogEntity,
        startTime: Long
    ) {
        addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Compound: checking both parts")

        // Check permissions for both compound parts — delegate to SafetyPipeline
        val allMissingPermissions = mutableListOf<String>()
        for (part in listOf("first", "second")) {
            val partTool = toolCall.args["${part}_tool"]?.let { element: kotlinx.serialization.json.JsonElement ->
                (element as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: continue
            allMissingPermissions.addAll(safetyPipeline.checkPermissionsForTool(partTool))
        }
        if (allMissingPermissions.isNotEmpty()) {
            addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Compound needs permissions")
            pendingCommand.set(sanitizedText)
            pendingInputType.set(inputType)
            onPermissionRequired?.invoke(allMissingPermissions.distinct())
            onPermissionRequiredMulticast.invokeAll { it(allMissingPermissions.distinct()) }
            return
        }

        // Check safety classification for both compound parts — delegate to SafetyPipeline
        var blocked = false
        var needsConfirmation = false
        var confirmationMessage = ""
        for (part in listOf("first", "second")) {
            val partTool = toolCall.args["${part}_tool"]?.let { element: kotlinx.serialization.json.JsonElement ->
                (element as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: continue
            val risk = safetyPipeline.classifyRisk(partTool, sanitizedText)
            addStep(AgentStatus.SAFETY_CHECK, "Safety Filter ($part)", "Risk: ${risk.name}")
            if (safetyPipeline.isBlocked(risk)) {
                addStep(AgentStatus.FAILED, "Security Block", "Compound part '$partTool' blocked for security.")
                blocked = true
            }
            if (safetyPipeline.requiresConfirmation(risk)) {
                needsConfirmation = true
                confirmationMessage = if (risk == RiskLevel.STRONG_CONFIRM) {
                    "SECURITY CHECK: One part ($partTool) is sensitive. Confirm both?"
                } else {
                    "Confirm: Execute compound command?"
                }
            }
        }
        if (blocked) {
            saveLog(log.copy(selectedTool = "compound", status = "blocked"))
            return
        }
        if (needsConfirmation) {
            addStep(AgentStatus.SAFETY_CHECK, "Confirmation Required", confirmationMessage)
            val confirmed = awaitConfirmation(confirmationMessage)
            if (!confirmed) {
                addStep(AgentStatus.FAILED, "Cancelled", "User declined compound confirmation")
                saveLog(log.copy(selectedTool = "compound", status = "cancelled"))
                return
            }
        }

        // Execute both parts — delegate to ActionExecutor
        addStep(AgentStatus.EXECUTING, "Agent Active", "Executing compound command...")
        val firstResult = actionExecutor.executeTool(buildCompoundPartToolCall(toolCall, "first"))
        val secondResult = actionExecutor.executeTool(buildCompoundPartToolCall(toolCall, "second"))

        val errors = listOfNotNull(
            (firstResult as? Result.Error)?.message,
            (secondResult as? Result.Error)?.message
        )
        val successes = listOfNotNull(
            (firstResult as? Result.Success)?.data,
            (secondResult as? Result.Success)?.data
        )

        val combined: Result<String> = when {
            errors.size == 2 -> Result.Error("Both parts failed: ${errors.joinToString("; ")}")
            errors.isNotEmpty() -> Result.Success("${successes.joinToString("; ")} [${errors.size} part(s) failed: ${errors.joinToString("; ")}]")
            else -> Result.Success(successes.joinToString("; "))
        }

        if (combined is Result.Error) {
            addStep(AgentStatus.FAILED, "Execution Error", combined.message)
        } else if (combined is Result.Success) {
            addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Compound complete")
            if (inputType == InputType.VOICE) {
                addStep(AgentStatus.SPEAKING, "Response", combined.data)
                voiceModule.speak(combined.data)
                    .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Compound speak failed: $msg") }
            }
        }
        saveLog(log.copy(selectedTool = "compound", status = if (combined is Result.Error) "failed" else "success"))
    }

    private fun buildCompoundPartToolCall(compound: ToolCall, which: String): ToolCall {
        val toolName = compound.args["${which}_tool"]?.let { element: kotlinx.serialization.json.JsonElement ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: "unknown"
        val argsJson = compound.args["${which}_args"]?.let { element: kotlinx.serialization.json.JsonElement ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: "{}"
        val args = try {
            kotlinx.serialization.json.Json.decodeFromString<kotlinx.serialization.json.JsonObject>(argsJson)
        } catch (e: Exception) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        return ToolCall(toolName, args)
    }

    private suspend fun awaitConfirmation(message: String): Boolean {
        // Prefer multicast if listeners are registered (both Activity and FloatingService)
        if (onConfirmationRequiredMulticast.hasListeners) {
            return suspendCancellableCoroutine { cont ->
                // First listener to respond wins — others are ignored
                var responded = false
                onConfirmationRequiredMulticast.invokeAll { listener ->
                    listener(message) { result ->
                        if (!responded) {
                            responded = true
                            cont.resumeWith(kotlin.Result.success(result))
                        }
                    }
                }
            }
        }

        // Fallback to legacy single-delegate callback for backward compatibility
        if (onConfirmationRequired == null) {
            Logger.w("Orchestrator: onConfirmationRequired is null — denying by default for safety")
            return false
        }
        return suspendCancellableCoroutine { cont ->
            onConfirmationRequired?.invoke(message) { result ->
                cont.resumeWith(kotlin.Result.success(result))
            } ?: run {
                Logger.w("Orchestrator: onConfirmationRequired became null during confirmation — denying")
                cont.resumeWith(kotlin.Result.success(false))
            }
        }
    }

    private fun addStep(status: AgentStatus, label: String, detail: String = "") {
        try {
            _timelineSteps.value = _timelineSteps.value + TimelineStep(status, label, detail)
        } catch (e: Exception) {
            Logger.e("Orchestrator: Failed to add timeline step", e)
        }
    }

    private fun releaseProcessingLock() {
        _isProcessing.value = false
        processingLock.set(false)
    }

    private suspend fun saveLog(log: ActionLogEntity) {
        try {
            withContext(Dispatchers.IO) { actionLogDao.insert(log) }
        } catch (e: Exception) { Logger.e("Log error", e) }
    }
}