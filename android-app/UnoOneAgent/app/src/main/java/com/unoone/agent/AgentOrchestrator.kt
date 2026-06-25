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
import com.unoone.agent.core.model.getOrNull
import com.unoone.agent.core.model.compoundSteps
import com.unoone.agent.core.safety.PermissionRequirement
import com.unoone.agent.core.util.CallbackMulticast
import com.unoone.agent.core.util.ConfirmationListener
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.util.PermissionListener
import com.unoone.agent.core.util.SystemPermissionListener
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Max wall-clock time to wait for a user confirmation before denying for safety (avoids a hung agent). */
private const val CONFIRMATION_TIMEOUT_MS = 60_000L

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
    // One OcrControl shared by the parser (OCR fallback for the context snapshot) and the
    // executor (read_screen / ocr_screen), so MediaProjection is initialized at most once.
    private val ocrControl = com.unoone.agent.phonecontrol.OcrControl(context)
    private val commandParser = CommandParser(
        accessibilityControl = com.unoone.agent.accessibilitycontrol.AccessibilityControl(),
        ocrControl = ocrControl,
        memoryModule = memoryModule,
        noteDao = noteDao,
        skillDao = skillDao
    )
    private val actionExecutor = ActionExecutor(
        context = context,
        noteDao = noteDao,
        skillDao = skillDao,
        memoryDao = memoryDao,
        actionLogDao = actionLogDao,
        phoneControl = com.unoone.agent.phonecontrol.PhoneControl(context),
        calendarControl = com.unoone.agent.phonecontrol.CalendarControl(context),
        ocrControl = ocrControl,
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
        actionExecutor._speak = { text -> speakText(text) }
        actionExecutor._recordVoiceNote = { durationSeconds -> recordVoiceNote(durationSeconds) }
    }

    /** Speaks text via the shared VoiceModule, used by the speak_response tool. */
    private fun speakText(text: String) {
        try {
            voiceModule.speak(text)
                .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: speak_response failed: $msg") }
        } catch (e: Exception) {
            Logger.e("Orchestrator: speak_response exception", e)
        }
    }

    /**
     * Records a voice memo for [durationSeconds] via the shared VoiceModule and returns the
     * offline STT transcription. Drives the `voice_recording` tool. RECORD_AUDIO is checked by
     * the safety pipeline before the tool executes, so the mic permission is granted here.
     */
    private suspend fun recordVoiceNote(durationSeconds: Int): Result<String> {
        return try {
            val start = voiceModule.startRecording(context, scope)
            if (start is Result.Error) return start
            kotlinx.coroutines.delay(durationSeconds * 1000L)
            voiceModule.stopAndTranscribe()
        } catch (e: Exception) {
            Result.Error("Voice recording failed: ${e.message}")
        }
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
    /** Surfaced when a tool needs non-runtime access (Accessibility / Overlay / MediaProjection). */
    var onSystemPermissionRequired: ((List<PermissionRequirement>) -> Unit)? = null

    // Thread-safe multicast callbacks — both MainActivity and FloatingAgentService
    // can register simultaneously without overwriting each other.
    val onPermissionRequiredMulticast = CallbackMulticast<PermissionListener>()
    val onConfirmationRequiredMulticast = CallbackMulticast<ConfirmationListener>()
    val onSystemPermissionRequiredMulticast = CallbackMulticast<SystemPermissionListener>()

    // Pending command for re-execution after permission grant (thread-safe)
    private val pendingCommand = AtomicReference<String?>(null)
    private val pendingInputType = AtomicReference<InputType?>(null)

    // Conversation context for the LLM planner: the last few commands and the result of the most
    // recent tool execution. Passed into the context snapshot so follow-ups ("do it again",
    // "the second one") can be disambiguated. Bound to the orchestrator instance (single user).
    private val recentCommands = java.util.ArrayDeque<String>()
    private var lastToolResult = ""

    /**
     * Injects the shared VoiceModule from the Application/ViewModel layer.
     * Called once at startup to eliminate the dual-instance problem.
     */
    fun setVoiceModule(shared: VoiceModule) {
        voiceModule = shared
    }

    /**
     * Loads a Gemma 3n E4B `.litertlm` model into the command parser's LiteRT-LM brain.
     * Should be called from a coroutine (engine init is slow).
     */
    suspend fun loadLlmModel(modelPath: String): com.unoone.agent.core.model.Result<Unit> {
        return commandParser.loadModel(modelPath)
    }

    /**
     * Unloads the Gemma brain to free native memory under system pressure
     * (see [com.unoone.agent.UnoOneApplication.onTrimMemory]). Idempotent.
     */
    fun unloadLlmModel() {
        commandParser.unloadModel()
    }

    /** True when the Gemma brain is loaded and available for LLM-backed planning. */
    fun isLlmLoaded(): Boolean = commandParser.isModelLoaded()

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

            // Record this command in the conversation ring buffer (capped at 3) so the next
            // command's LLM snapshot can see it. contextCommands holds the PRIOR commands only.
            val contextCommands = recentCommands.toList()
            recentCommands.addLast(sanitizedText)
            while (recentCommands.size > 3) recentCommands.removeFirst()

            // Step 1: Check if this triggers a custom Skill
            val skill = skillsModule.findSkillByTrigger(sanitizedText)
            if (skill != null) {
                addStep(AgentStatus.TOOL_SELECTED, "Executing Skill", skill.name)
                val steps = skillsModule.getSkillSteps(skill)
                for (step in steps) {
                    addStep(AgentStatus.EXECUTING, "Skill Step", step)
                    val toolCall = commandParser.parse(step) ?: continue
                    // Skills no longer bypass safety: each step runs the full pipeline
                    // (permissions → risk → block → confirm → execute → audit) just like a
                    // standalone command. On any NeedsAccess/Blocked/Cancelled we stop the skill.
                    val outcome = runValidatedToolCall(toolCall, step)
                    when (outcome) {
                        is StepOutcome.NeedsSystemAccess -> {
                            addStep(AgentStatus.FAILED, "Skill Paused", "Needs system access for ${toolCall.tool}")
                            onSystemPermissionRequired?.invoke(outcome.missing)
                            onSystemPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                            saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "blocked"))
                            // Remember the command so clearPendingAndReExecute() can resume the skill
                            // after the user grants the missing system access.
                            pendingCommand.set(sanitizedText)
                            pendingInputType.set(inputType)
                            releaseProcessingLock()
                            return
                        }
                        is StepOutcome.NeedsRuntimeAccess -> {
                            addStep(AgentStatus.FAILED, "Skill Paused", "Needs runtime permissions for ${toolCall.tool}")
                            onPermissionRequired?.invoke(outcome.missing)
                            onPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                            saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "blocked"))
                            pendingCommand.set(sanitizedText)
                            pendingInputType.set(inputType)
                            releaseProcessingLock()
                            return
                        }
                        is StepOutcome.Blocked -> {
                            lastToolResult = "Blocked: ${toolCall.tool}"
                            saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "blocked"))
                            releaseProcessingLock()
                            return
                        }
                        is StepOutcome.Cancelled -> {
                            lastToolResult = "Cancelled"
                            saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "cancelled"))
                            releaseProcessingLock()
                            return
                        }
                        is StepOutcome.Executed -> {
                            if (outcome.result is Result.Error) {
                                lastToolResult = outcome.result.message
                                saveLog(log.copy(
                                    selectedTool = "skill:${skill.name}",
                                    status = "failed",
                                    errorMessage = outcome.result.message
                                ))
                                releaseProcessingLock()
                                return
                            }
                            // success → continue to the next skill step
                        }
                    }
                }
                addStep(AgentStatus.DONE, "Skill Complete", "Sequence finished successfully")
                lastToolResult = "Skill ${skill.name} complete"
                saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "success", modelLatencyMs = System.currentTimeMillis() - startTime))
                releaseProcessingLock()
                return
            }

            // Step 2: Planning / Intent Extraction — delegate to CommandParser
            val toolCall = commandParser.parseAsync(sanitizedText, contextCommands, lastToolResult)
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

            // Steps 3–5: Permission check → risk classification → block/confirm → execute.
            // All four phases now share [runValidatedToolCall] with the skill path so safety can
            // never be bypassed by either entry point.
            val outcome = runValidatedToolCall(toolCall, sanitizedText)
            when (outcome) {
                is StepOutcome.NeedsSystemAccess -> {
                    pendingCommand.set(text)
                    pendingInputType.set(inputType)
                    onSystemPermissionRequired?.invoke(outcome.missing)
                    onSystemPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                    releaseProcessingLock()
                    return
                }
                is StepOutcome.NeedsRuntimeAccess -> {
                    pendingCommand.set(text)
                    pendingInputType.set(inputType)
                    onPermissionRequired?.invoke(outcome.missing)
                    onPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                    releaseProcessingLock()
                    return
                }
                is StepOutcome.Blocked -> {
                    lastToolResult = "Blocked: ${toolCall.tool}"
                    saveLog(log.copy(selectedTool = toolCall.tool, status = "blocked"))
                    releaseProcessingLock()
                    return
                }
                is StepOutcome.Cancelled -> {
                    lastToolResult = "Cancelled"
                    saveLog(log.copy(selectedTool = toolCall.tool, status = "cancelled"))
                    releaseProcessingLock()
                    return
                }
                is StepOutcome.Executed -> {
                    val result = outcome.result
                    if (result is Result.Error) {
                        lastToolResult = result.message
                        saveLog(log.copy(selectedTool = toolCall.tool, status = "failed", errorMessage = result.message))
                        releaseProcessingLock()
                        return
                    }

                    // Step 6: Feedback & Verification
                    val responseText = if (result is Result.Success) result.data.toString() else "Action completed."

                    // speak_response already produced audio via ActionExecutor._speak; don't double-speak.
                    if (toolCall.tool == "speak_response") {
                        addStep(AgentStatus.DONE, "Done", responseText)
                    } else if (inputType == InputType.VOICE) {
                        addStep(AgentStatus.SPEAKING, "Response", responseText)
                        voiceModule.speak(responseText)
                            .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Response speak failed: $msg") }
                    } else {
                        addStep(AgentStatus.DONE, "Done", responseText)
                    }

                    saveLog(log.copy(
                        selectedTool = toolCall.tool,
                        toolArgsJson = toolCall.args.toString(),
                        status = "success",
                        modelLatencyMs = System.currentTimeMillis() - startTime
                    ))
                    lastToolResult = responseText
                }
            }
        } catch (e: Exception) {
            Logger.e("Master Orchestrator Exception", e)
            addStep(AgentStatus.FAILED, "System Error", e.localizedMessage ?: "Error")
        } finally {
            // Single release point. releaseProcessingLock() already sets processingLock=false;
            // the extra set(false) was dead code that could clobber a concurrent command's lock
            // in the (suspension-free) window between an early return's release and this finally.
            releaseProcessingLock()
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
     * Handles compound tool calls. Each step runs through the full [runValidatedToolCall]
     * pipeline (permissions -> risk -> block -> confirm -> execute -> audit), exactly like a
     * standalone command, in declared order. Execution stops at the first step that needs
     * access, is blocked, or is cancelled.
     */
    private suspend fun handleCompoundCommand(
        toolCall: ToolCall,
        sanitizedText: String,
        inputType: InputType,
        log: ActionLogEntity,
        startTime: Long
    ) {
        val steps = toolCall.compoundSteps()
        addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Compound: ${steps.size} step(s)")

        val results = mutableListOf<Result<String>>()
        for ((index, step) in steps.withIndex()) {
            addStep(AgentStatus.EXECUTING, "Compound Step ${index + 1}/${steps.size}", step.tool)
            val outcome = runValidatedToolCall(step, sanitizedText)
            when (outcome) {
                is StepOutcome.NeedsSystemAccess -> {
                    addStep(AgentStatus.FAILED, "Compound Paused", "Needs system access for ${step.tool}")
                    onSystemPermissionRequired?.invoke(outcome.missing)
                    onSystemPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                    saveLog(log.copy(selectedTool = "compound", status = "blocked"))
                    pendingCommand.set(sanitizedText)
                    pendingInputType.set(inputType)
                    return
                }
                is StepOutcome.NeedsRuntimeAccess -> {
                    addStep(AgentStatus.FAILED, "Compound Paused", "Needs runtime permissions for ${step.tool}")
                    pendingCommand.set(sanitizedText)
                    pendingInputType.set(inputType)
                    onPermissionRequired?.invoke(outcome.missing)
                    onPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                    saveLog(log.copy(selectedTool = "compound", status = "blocked"))
                    return
                }
                is StepOutcome.Blocked -> {
                    addStep(AgentStatus.FAILED, "Security Block", "Compound step '${step.tool}' blocked for security.")
                    lastToolResult = "Blocked: ${step.tool}"
                    saveLog(log.copy(selectedTool = "compound", status = "blocked"))
                    return
                }
                is StepOutcome.Cancelled -> {
                    addStep(AgentStatus.FAILED, "Cancelled", "User declined compound confirmation")
                    lastToolResult = "Cancelled"
                    saveLog(log.copy(selectedTool = "compound", status = "cancelled"))
                    return
                }
                is StepOutcome.Executed -> results.add(outcome.result)
            }
        }

        // Combine per-step outcomes into one compound result.
        val errors = mutableListOf<String>()
        val successes = mutableListOf<String>()
        for (r in results) {
            when (r) {
                is Result.Error -> errors.add(r.message)
                is Result.Success -> successes.add(r.data.toString())
            }
        }
        val combined: Result<String> = when {
            results.isEmpty() -> Result.Error("Compound produced no executable steps")
            errors.size == results.size -> Result.Error("All parts failed: ${errors.joinToString("; ")}")
            errors.isNotEmpty() -> Result.Success("${successes.joinToString("; ")} [${errors.size} part(s) failed: ${errors.joinToString("; ")}]")
            else -> Result.Success(successes.joinToString("; "))
        }

        if (combined is Result.Error) {
            addStep(AgentStatus.FAILED, "Execution Error", combined.message)
            lastToolResult = combined.message
        } else {
            addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Compound complete")
            lastToolResult = combined.getOrNull() ?: "Compound complete"
            if (inputType == InputType.VOICE) {
                val responseText = combined.getOrNull() ?: ""
                addStep(AgentStatus.SPEAKING, "Response", responseText)
                voiceModule.speak(responseText)
                    .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: Compound speak failed: $msg") }
            }
        }
        saveLog(log.copy(
            selectedTool = "compound",
            status = if (combined is Result.Error) "failed" else "success",
            modelLatencyMs = System.currentTimeMillis() - startTime
        ))
    }

    /**
     * Outcome of running one tool call through the full safety pipeline. Shared by the normal
     * command path and the skill-step path so neither can bypass safety.
     */
    private sealed class StepOutcome {
        /** Tool executed; [result] is Success or Error. */
        data class Executed(val result: Result<String>) : StepOutcome()
        /** Missing runtime (dangerous) permissions — caller should request them and re-run. */
        data class NeedsRuntimeAccess(val missing: List<String>) : StepOutcome()
        /** Missing non-runtime access (Accessibility / Overlay / MediaProjection). */
        data class NeedsSystemAccess(val missing: List<PermissionRequirement>) : StepOutcome()
        /** Blocked by SafetyGuard. */
        data class Blocked(val riskLevel: RiskLevel) : StepOutcome()
        /** User declined (or no listener responded to) the confirmation prompt. */
        object Cancelled : StepOutcome()
    }

    /**
     * Runs one [ToolCall] through permission check → risk classification → block/confirm →
     * execute, emitting timeline steps and audit log entries along the way. Returns the
     * [StepOutcome] so the caller can decide how to react (request access, stop a skill, etc.).
     *
     * System access (Accessibility/Overlay/MediaProjection) is checked first because those cannot
     * be granted via the runtime permission flow — the caller must surface them via
     * [onSystemPermissionRequired]. Runtime permissions are surfaced via [onPermissionRequired].
     */
    private suspend fun runValidatedToolCall(toolCall: ToolCall, sanitizedText: String): StepOutcome {
        // 1. Non-runtime system access (Accessibility / Overlay / MediaProjection)
        val unsatisfiedSystem = safetyPipeline.unsatisfiedRequirements(toolCall.tool)
            .filterNot { it is PermissionRequirement.RuntimePerm }
        if (unsatisfiedSystem.isNotEmpty()) {
            addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs system access for ${toolCall.tool}")
            return StepOutcome.NeedsSystemAccess(unsatisfiedSystem)
        }

        // 2. Runtime (dangerous) permissions
        val missingPermissions = safetyPipeline.checkPermissionsForTool(toolCall.tool)
        if (missingPermissions.isNotEmpty()) {
            addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs permissions for ${toolCall.tool}")
            return StepOutcome.NeedsRuntimeAccess(missingPermissions)
        }

        // 3. Risk classification (tool risk + input risk, max wins)
        val riskLevel = safetyPipeline.classifyRisk(toolCall.tool, sanitizedText)
        addStep(AgentStatus.SAFETY_CHECK, "Safety Filter", "Risk: ${riskLevel.name}")

        if (safetyPipeline.isBlocked(riskLevel)) {
            addStep(AgentStatus.FAILED, "Security Block", "Action blocked for security.")
            AuditLogger.log(toolCall.tool, riskLevel, "blocked", sanitizedText)
            return StepOutcome.Blocked(riskLevel)
        }

        if (safetyPipeline.requiresConfirmation(riskLevel)) {
            val confirmationMessage = safetyPipeline.confirmationMessage(toolCall.tool, riskLevel)
            addStep(AgentStatus.SAFETY_CHECK, "Confirmation Required", confirmationMessage)
            val confirmed = awaitConfirmation(confirmationMessage)
            if (!confirmed) {
                addStep(AgentStatus.FAILED, "Cancelled", "User declined confirmation")
                AuditLogger.log(toolCall.tool, riskLevel, "cancelled", sanitizedText)
                return StepOutcome.Cancelled
            }
        }

        // 4. Execute
        addStep(AgentStatus.EXECUTING, "Agent Active", "Executing ${toolCall.tool}...")
        val execStart = System.currentTimeMillis()
        val result = actionExecutor.executeTool(toolCall)
        com.unoone.agent.observability.Diagnostics.recordToolExecution(
            toolCall.tool, System.currentTimeMillis() - execStart, result is Result.Success
        )
        if (result is Result.Error) {
            addStep(AgentStatus.FAILED, "Execution Error", result.message)
        } else {
            addStep(AgentStatus.VERIFYING, "Verifying Outcome", "Task complete")
        }
        return StepOutcome.Executed(result)
    }

    private suspend fun awaitConfirmation(message: String): Boolean {
        // Prefer multicast if listeners are registered (both Activity and FloatingService)
        if (onConfirmationRequiredMulticast.hasListeners) {
            // Bounded wait: if no listener calls back (UI not foregrounded, callback swallowed),
            // deny for safety instead of hanging the agent forever with the processing lock held.
            return withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // First listener to respond wins — others are ignored. AtomicBoolean so two
                    // listeners invoking the callback concurrently can't double-resume the cont.
                    val responded = java.util.concurrent.atomic.AtomicBoolean(false)
                    onConfirmationRequiredMulticast.invokeAll { listener ->
                        listener(message) { result ->
                            if (responded.compareAndSet(false, true)) {
                                cont.resumeWith(kotlin.Result.success(result))
                            }
                        }
                    }
                }
            } ?: run {
                Logger.w("Orchestrator: confirmation timed out after ${CONFIRMATION_TIMEOUT_MS}ms — denying for safety")
                false
            }
        }

        // Fallback to legacy single-delegate callback for backward compatibility
        if (onConfirmationRequired == null) {
            Logger.w("Orchestrator: onConfirmationRequired is null — denying by default for safety")
            return false
        }
        return withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                onConfirmationRequired?.invoke(message) { result ->
                    cont.resumeWith(kotlin.Result.success(result))
                } ?: run {
                    Logger.w("Orchestrator: onConfirmationRequired became null during confirmation — denying")
                    cont.resumeWith(kotlin.Result.success(false))
                }
            }
        } ?: run {
            Logger.w("Orchestrator: confirmation timed out after ${CONFIRMATION_TIMEOUT_MS}ms — denying for safety")
            false
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