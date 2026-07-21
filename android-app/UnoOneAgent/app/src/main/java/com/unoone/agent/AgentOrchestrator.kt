package com.unoone.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.model.onError
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.model.getOrNull
import com.unoone.agent.core.model.compoundSteps
import com.unoone.agent.core.agent.LoopDecision
import com.unoone.agent.core.agent.ReActLoopController
import com.unoone.agent.core.agent.SafetyJudgePolicy
import com.unoone.agent.core.agent.IntentClassifier
import com.unoone.agent.core.agent.IntentType
import com.unoone.agent.core.agent.NarrationPolicy
import com.unoone.agent.core.agent.StopReason
import com.unoone.agent.core.agent.ToolHealthTracker
import com.unoone.agent.core.agent.BrainHealthPolicy
import com.unoone.agent.core.agent.BlindAidNarrator
import com.unoone.agent.core.agent.VoiceResponseLocalizer
import com.unoone.agent.core.agent.VoiceFastReply
import com.unoone.agent.core.safety.PermissionRequirement
import com.unoone.agent.core.util.CallbackMulticast
import com.unoone.agent.core.util.ConfirmationListener
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.util.PermissionListener
import com.unoone.agent.core.util.SystemPermissionListener
import com.unoone.agent.execution.ActionExecutor
import com.unoone.agent.parsing.CommandParser
import com.unoone.agent.parsing.ParseOutcome
import com.unoone.agent.safety.AuditLogger
import com.unoone.agent.safety.SafetyPipeline
import com.unoone.agent.safety.SecurityLevel
import com.unoone.agent.skills.SkillsModule
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.ActionLogEntity
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.VoiceAgentRuntime
import com.unoone.agent.voice.VoiceAgentState
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Max wall-clock time to wait for a user confirmation before denying for safety (avoids a hung agent). */
private const val CONFIRMATION_TIMEOUT_MS = 60_000L

/**
 * Minimum gap between spoken step narrations (eyes-free/WS2). Rapid timeline milestones are throttled
 * so a blind user isn't flooded with cues; the final answer is spoken through the same serialized
 * channel ([AgentOrchestrator.speakAnswer]) so it never overlaps a queued milestone.
 */
private const val NARRATION_MIN_INTERVAL_MS = 1_200L

/**
 * Enables the second-pass LLM safety judge on every validated tool step when the brain is loaded.
 * The judge only escalates risk (never weakens it); this flag exists so the per-step latency cost of
 * an extra on-device inference can be disabled without removing the feature. Default on: the whole
 * point of the judge is to catch paraphrased harm the keyword filter misses.
 */
private const val SAFETY_JUDGE_ENABLED = true

/**
 * Enables diagnostics self-heal: (a) a tool is flagged flaky and surfaced after enough recent
 * failures (ToolHealthTracker), and (b) the brain is auto-reloaded when it is found down after having
 * been loaded (it closes itself on a 30s inference timeout and otherwise nothing reloads it until
 * onResume). The control decisions are JVM-tested; the reload + spoken diagnostic are device-time.
 */
private const val SELF_HEAL_ENABLED = true

/**
 * Enables streaming first-turn LLM planning: when the LLM path is taken (no rule matched + a model
 * is loaded), partial model text is surfaced to the timeline as it streams, instead of waiting for
 * the full inference. On any streaming failure the orchestrator falls back to the synchronous
 * [com.unoone.agent.parsing.CommandParser.parseAsyncWithProvenance] path, so the device-verified
 * behavior is preserved. The pure delta reduction is JVM-tested; the LiteRT-LM `Flow` is
 * device-time-only (see [com.unoone.agent.core.agent.StreamingTextReducer]).
 */
private const val STREAMING_INFERENCE_ENABLED = true

/**
 * Multimodal vision gate for `describe_scene`. False by default: the shipped Gemma 4 E2B
 * `.litertlm` artifact is text-only (no vision weights), so the LiteRT-LM
 * `Content.ImageBytes` path ([com.unoone.agent.localbrain.GemmaPlanner.describeSceneWithVision]) is
 * wired against the real AAR but INACTIVE. `describe_scene` instead uses the always-available OCR
 * + foreground-context description ([com.unoone.agent.core.agent.SceneDescriptionBuilder]), which is
 * JVM-tested and works today. Flip this true only after a vision-capable `.litertlm` artifact is
 * loaded AND verified on the device matrix — never silently.
 */
private const val VISION_MODEL_ENABLED = false

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

    // ---- Eyes-free (WS2) step narration -----------------------------------------------------
    // The input type of the command currently being processed; set at processCommand entry so addStep
    // knows whether to speak milestones. VOICE commands always narrate; TEXT commands narrate only
    // when [narrateTextCommands] is toggled on (default off — text users have the timeline to read).
    @Volatile
    private var currentInputType: InputType = InputType.TEXT

    /** When true, TEXT commands also get spoken step narration (default off; VOICE always narrates). */
    @Volatile
    var narrateTextCommands: Boolean = false

    /**
     * Single serialized TTS channel. Milestone narration is launched async under this mutex; the
     * final answer ([speakAnswer]) acquires it too, so a queued milestone drains before the answer
     * plays — narration and the final answer never overlap on the speaker.
     */
    private val speakMutex = Mutex()

    /** Timestamp of the last spoken milestone; used by the [NARRATION_MIN_INTERVAL_MS] throttle. */
    private val lastNarrationAt = AtomicLong(0L)
    // ----------------------------------------------------------------------------------------

    // Extracted components — Phase 1A: God object split
    private val memoryModule = com.unoone.agent.memory.MemoryModule(memoryDao)
    // One OcrControl shared by the parser (OCR fallback for the context snapshot) and the
    // executor (read_screen / ocr_screen), so MediaProjection is initialized at most once.
    private val ocrControl = com.unoone.agent.phonecontrol.OcrControl(context)
    // One AccessibilityControl shared by the parser (context snapshot) and the executor
    // (system_control / read_screen) so both observe the same AccessibilityService static state
    // and never diverge on the current foreground package/activity.
    private val accessibilityControl = com.unoone.agent.accessibilitycontrol.AccessibilityControl()
    private val commandParser = CommandParser(
        accessibilityControl = accessibilityControl,
        ocrControl = ocrControl,
        memoryModule = memoryModule,
        noteDao = noteDao,
        skillDao = skillDao,
        voiceLanguageProvider = { currentVoiceLanguageCode() }
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
        accessibilityControl = accessibilityControl,
        agentRouter = com.unoone.agent.agentrouter.AgentRouter()
    )
    private val safetyPipeline = SafetyPipeline(
        context = context,
        safetyGuard = com.unoone.agent.safetyguard.SafetyGuard()
    )

    val skillsModule = SkillsModule(skillDao, memoryDao)

    // Wire ActionExecutor callbacks to orchestrator state
    init {
        actionExecutor._skillsModule = skillsModule
        actionExecutor._setBlindAidActive = { active -> setBlindAidActiveFromTool(active) }
        actionExecutor._recordVoiceNote = { durationSeconds -> recordVoiceNote(durationSeconds) }
        // Multimodal vision for describe_scene — INACTIVE until a vision-capable .litertlm artifact
        // ships (the loaded Gemma 4 E2B artifact is text-only). When VISION_MODEL_ENABLED
        // is false the callback stays null and describe_scene uses the always-available OCR + context
        // fallback ([com.unoone.agent.core.agent.SceneDescriptionBuilder]).
        if (VISION_MODEL_ENABLED) {
            actionExecutor._describeSceneWithVision = { imageBytes, aspect ->
                commandParser.describeSceneWithVision(imageBytes, aspect)
            }
        }
        actionExecutor._openSecureBrowserTask = { origin, task -> openSecureBrowserTask(origin, task) }
        actionExecutor._prepareDocumentFill = { format -> onDocumentFillRequest?.invoke(format) }
    }

    /**
     * Eyes-free (WS4): UI-owned handler invoked by the `secure_browser_task` tool. Set by
     * MainActivity (which owns the SecureBrowserViewModel + nav controller) via AgentViewModel. The
     * handler navigates to the Secure Browser screen and stashes the pending (origin, task) so the
     * PageAgent run starts once the Gemma lease is acquired and the runtime is ready. When null the
     * tool returns a handled "not available" error instead of a fake success. The live executeTask +
     * spoken page read are device-time gates (see DEVICE_VERIFICATION.md).
     */
    @Volatile
    var onSecureBrowserTask: ((origin: String, task: String) -> Unit)? = null

    /** UI-owned picker request emitted by the offline Document Agent tool. */
    @Volatile
    var onDocumentFillRequest: ((format: String) -> Unit)? = null

    private fun openSecureBrowserTask(origin: String, task: String): Result<String> {
        val handler = onSecureBrowserTask
            ?: return Result.Error(
                "Secure Browser is not available right now. Open it from the main page first."
            )
        handler(origin, task)
        return if (task.isBlank()) Result.Success("Opening Secure Browser for $origin.")
        else Result.Success("Opening Secure Browser for $origin. I'll start: $task")
    }

    /**
     * The user's currently selected voice/TTS language code, read fresh per call from the same
     * `unoone_settings`/`voice_language` preference the voice module uses (so a Settings change
     * takes effect on the next command without a restart). Surfaced to the planner via the context
     * snapshot so the model keeps its reply in the user's language. Fails safe to the default
     * ("en") if the preference cannot be read.
     */
    private fun currentVoiceLanguageCode(): String = try {
        context.getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)
            .getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT) ?: VoiceLanguage.DEFAULT
    } catch (_: Exception) {
        VoiceLanguage.DEFAULT
    }

    /**
     * Applies an explicit spoken language request without involving Gemma. Rebuilding both native
     * speech engines is serialized by VoiceModule and owns the foreground-task gate so the wake
     * recorder cannot race the model swap. The preference is committed only after both offline
     * engines load; on failure the previous runtime is restored.
     */
    private suspend fun applyVoiceLanguageCommand(
        requestedCode: String,
        inputType: InputType
    ): Boolean {
        val previousCode = VoiceLanguage.normalize(currentVoiceLanguageCode())
        val requested = VoiceLanguage.normalize(requestedCode)
        val modelBaseDir =
            (context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath) +
                "/models"
        addStep(
            AgentStatus.UNDERSTANDING,
            "Changing voice language",
            VoiceLanguage.displayName(requested)
        )

        var switched = requested == previousCode
        if (!switched) {
            VoiceService.beginForegroundTask()
            try {
                VoiceAgentRuntime.transition(
                    VoiceAgentState.INITIALISING,
                    "switching offline voice language"
                )
                val (sttResult, ttsResult) = withContext(Dispatchers.IO) {
                    voiceModule.reinitForLanguage(modelBaseDir, requested)
                }
                switched = sttResult is Result.Success && ttsResult is Result.Success
                if (switched) {
                    val preferences =
                        context.getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)
                    preferences.edit(commit = true) {
                        putString(VoiceLanguage.PREF_KEY, requested)
                    }
                    switched = preferences.getString(VoiceLanguage.PREF_KEY, previousCode) == requested
                }
                if (!switched) {
                    withContext(Dispatchers.IO) {
                        voiceModule.reinitForLanguage(modelBaseDir, previousCode)
                    }
                }
            } finally {
                VoiceService.endForegroundTask()
            }
        }

        val response = if (switched) {
            VoiceLanguage.changeConfirmation(requested)
        } else {
            VoiceLanguage.changeFailure(requested, previousCode)
        }
        if (switched) {
            addStep(AgentStatus.DONE, "Voice language changed", response)
            VoiceAgentRuntime.recordOutcome("voice language changed", "offline STT and TTS loaded")
        } else {
            addStep(AgentStatus.FAILED, "Voice language unavailable", response)
            VoiceAgentRuntime.recordError(
                "VOICE_LANGUAGE_UNAVAILABLE",
                "Install or repair the offline ${VoiceLanguage.displayName(requested)} speech pack"
            )
        }
        if (inputType == InputType.VOICE || narrateTextCommands) {
            addStep(AgentStatus.SPEAKING, "Response", response)
            speakAnswer(response)
        }
        lastToolResult = response
        saveLog(
            ActionLogEntity(
                inputText = "[private language command]",
                inputType = inputType.name.lowercase(),
                selectedTool = "change_voice_language",
                status = if (switched) "success" else "failed"
            )
        )
        return switched
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

    // ---- C3: cooperative cancel via run-generation tokens ------------------------------
    // Each processCommand run increments currentRunId and captures its own generation. Cancel
    // stamps cancelledRunId with the latest run id; checkpoints compare the two so a cancel only
    // ever stops the run it was aimed at — a NEW command (incremented id) is never affected, and a
    // stale cancel from a previous run can't block a fresh one. Avoids the shared-flag race.
    private val currentRunId = AtomicLong(0)
    private val cancelledRunId = AtomicLong(0)

    // ---- C1: Blind Aid brain lease hooks (set by UnoOneApplication) --------------------
    // Blind Aid is a pure CameraX + ML Kit path that never uses the Gemma brain, so on activation
    // we unload the 2.5 GB brain to free ~800 MB RAM (the reported "system shuts down" OOM kill).
    // The Application owns the Secure Browser lease + ExclusiveBrainLeaseState, so it sets this
    // guard to "safe to unload" and a reload callback that honours those leases on deactivation.
    var brainReleaseGuard: () -> Boolean = { true }
    var brainReloadCallback: (() -> Unit)? = null
    var brainLoadCancelCallback: (() -> Unit)? = null

    private val _isBlindAidActive = MutableStateFlow(false)
    val isBlindAidActive: StateFlow<Boolean> = _isBlindAidActive.asStateFlow()
    private val blindAidActivationInFlight = AtomicBoolean(false)

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
    // C4: the system permission the pending command was waiting on, so clearPendingAndReExecute can
    // re-check it on resume and NOT blindly re-run (which bounced back to system settings in a loop).
    private val pendingRequiredPermission = AtomicReference<PermissionRequirement?>(null)

    // Conversation context for the LLM planner: the last few commands and the result of the most
    // recent tool execution. Passed into the context snapshot so follow-ups ("do it again",
    // "the second one") can be disambiguated. Bound to the orchestrator instance (single user).
    private val recentCommands = java.util.ArrayDeque<String>()
    private var lastToolResult = ""

    // Self-heal state (diagnostics): rolling per-tool health + remembered brain load for auto-reload.
    private val toolHealthTracker = ToolHealthTracker()
    private val flaggedFlakyTools = mutableSetOf<String>()
    private var lastLoadedPath: String? = null
    private var lastLoadedSpec: com.unoone.agent.core.model.BrainModelSpec? = null
    private var consecutiveInferenceFailures = 0

    /**
     * Injects the shared VoiceModule from the Application/ViewModel layer.
     * Called once at startup to eliminate the dual-instance problem.
     */
    fun setVoiceModule(shared: VoiceModule) {
        voiceModule = shared
    }

    /**
     * Loads a `.litertlm` brain model (default profile) into the command parser's LiteRT-LM engine.
     * Should be called from a coroutine (engine init is slow).
     */
    suspend fun loadLlmModel(modelPath: String): com.unoone.agent.core.model.Result<Unit> {
        val result = commandParser.loadModel(modelPath)
        if (result is Result.Success) {
            lastLoadedPath = modelPath
            consecutiveInferenceFailures = 0
        }
        return result
    }

    /**
     * Explicit Gemma 4 E2B load — loads [modelPath] using [spec] through the same safe
     * GemmaPlanner interface. Should be called from a coroutine.
     */
    suspend fun loadLlmModel(
        modelPath: String,
        spec: com.unoone.agent.core.model.BrainModelSpec
    ): com.unoone.agent.core.model.Result<Unit> {
        if (!AgentRuntimeGate.isEnabled()) return Result.Error("UnoOne is disabled")
        val result = commandParser.loadModel(modelPath, spec)
        if (result is Result.Success) {
            lastLoadedPath = modelPath
            lastLoadedSpec = spec
            consecutiveInferenceFailures = 0
        }
        return result
    }

    /**
     * Unloads the Gemma brain to free native memory under system pressure
     * (see [com.unoone.agent.UnoOneApplication.onTrimMemory]). Idempotent.
     */
    fun unloadLlmModel() {
        commandParser.unloadModel()
    }

    /**
     * Self-heal reload: the brain was loaded but is now down (it auto-closes on a 30s inference
     * timeout, and nothing else reloads it until onResume). Reloads from the remembered path/spec,
     * surfaces a timeline step, and audit-logs the outcome. Returns true on a successful reload.
     * Device-time; the decision to call it is made by the caller (processCommand proactive check or
     * the ReAct loop's inference-failure check via [BrainHealthPolicy]).
     */
    private suspend fun selfHealReloadBrain(): Boolean {
        if (!AgentRuntimeGate.isEnabled()) return false
        val path = lastLoadedPath ?: return false
        addStep(AgentStatus.EXECUTING, "Recovering", "Brain dropped — reloading…")
        val result = if (lastLoadedSpec != null) commandParser.loadModel(path, lastLoadedSpec!!)
                     else commandParser.loadModel(path)
        val ok = result is Result.Success
        if (!AgentRuntimeGate.isEnabled()) {
            commandParser.unloadModel()
            return false
        }
        if (ok) {
            consecutiveInferenceFailures = 0
            AuditLogger.log("brain_reload", RiskLevel.DIRECT, "recovered", "self-heal")
            addStep(AgentStatus.VERIFYING, "Recovered", "Brain reloaded.")
        } else {
            AuditLogger.log("brain_reload", RiskLevel.DIRECT, "recovery_failed", "self-heal")
            addStep(AgentStatus.FAILED, "Recovery Failed", "Brain could not reload — rule-only mode.")
        }
        return ok
    }

    /** True when the Gemma brain is loaded and available for LLM-backed planning. */
    fun isLlmLoaded(): Boolean = commandParser.isModelLoaded()

    /** The profile currently loaded into the brain, or null when no model is loaded. */
    fun loadedBrainProfile(): com.unoone.agent.core.model.BrainModelSpec? = commandParser.loadedProfile()

    /** Actual runtime backend ("GPU"/"CPU") of the loaded brain, or "" if not loaded. */
    fun loadedBrainBackend(): String = commandParser.activeBackend()

    /** Last load error (empty on success) — surfaces device-compatibility status to the UI. */
    fun lastBrainLoadError(): String = commandParser.lastLoadError()

    /**
     * Plans a single tool call for [command] **without executing it**. A read-only probe used by the
     * Brain Self-Test to verify on-device that the loaded brain loads and produces an accepted tool
     * call. Tries the rule-based fast path first, then the Gemma planner. No safety gate, no
     * permissions, no execution — this never performs a phone action. Returns the proposed
     * [com.unoone.agent.core.model.ToolCall] or an error when nothing could be planned.
     */
    suspend fun planToolCall(command: String): com.unoone.agent.core.model.Result<com.unoone.agent.core.model.ToolCall> {
        val call = commandParser.parseAsync(command, emptyList(), "")
        return if (call != null) com.unoone.agent.core.model.Result.Success(call)
        else com.unoone.agent.core.model.Result.Error("No tool call proposed for: $command")
    }

    fun setBlindAidActive(
        active: Boolean,
        bringToForeground: Boolean = false,
        announce: Boolean = true
    ) {
        if (active && !AgentRuntimeGate.isEnabled()) return
        if (active) {
            if (!blindAidActivationInFlight.compareAndSet(false, true)) return
            brainLoadCancelCallback?.invoke()
            // C1: free the 2.5 GB Gemma brain BEFORE binding the camera. Blind Aid is a pure
            // CameraX + MediaPipe path — it never uses the brain — and on a ~5 GB-available device
            // keeping the brain resident while the camera + object detector load trips the kernel
            // lowmemorykiller and kills the app. Native release must not run on the UI thread: wait
            // for the IO handoff, then expose the camera state so camera and Gemma never overlap.
            scope.launch {
                try {
                    if (isLlmLoaded() && !processingLock.get() && brainReleaseGuard()) {
                        runCatching { withContext(Dispatchers.IO) { unloadLlmModel() } }
                            .onSuccess {
                                Logger.i("Orchestrator: unloaded Gemma brain for Blind Aid (RAM freed for camera)")
                            }
                            .onFailure { Logger.e("Orchestrator: brain unload for Blind Aid failed", it) }
                    }
                    _isBlindAidActive.value = true
                    if (bringToForeground) bringAppToForegroundIfNeeded()
                    // Accessibility disclaimer: Blind Aid is assistive guidance, not a certified
                    // navigation or medical-safety device. Spoken once on activation.
                    if (announce) {
                        voiceModule.speakAwait(
                            BlindAidNarrator.activationMessage(currentVoiceLanguageCode())
                        ).onError { msg: String, _: Throwable? ->
                            Logger.e("Orchestrator: Blind aid speak failed: $msg")
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Orchestrator: Blind Aid activation failed", e)
                } finally {
                    blindAidActivationInFlight.set(false)
                }
            }
        } else {
            blindAidActivationInFlight.set(false)
            // Flush a currently-playing/queued scene before announcing the mode transition.
            voiceModule.stopSpeaking()
            _isBlindAidActive.value = false
            if (announce) {
                scope.launch {
                    kotlinx.coroutines.delay(150L)
                    voiceModule.speakAwait(
                        BlindAidNarrator.deactivationMessage(currentVoiceLanguageCode())
                    )
                        .onError { msg: String, _: Throwable? ->
                            Logger.e("Orchestrator: Blind aid speak failed: $msg")
                        }
                }
            }
            // C1: restore the brain for chat/agent commands. The Application's reloader honours the
            // exclusive-lease guards so it won't fight a Secure Browser session.
            brainReloadCallback?.invoke()
        }
    }

    /**
     * Voice/agent actions run while [processingLock] is held. The public UI path deliberately avoids
     * unloading Gemma during an in-flight command, so the tool path must release it explicitly before
     * CameraX and ML Kit are started. This prevents the voice activation path from retaining both
     * multi-gigabyte workloads at once on memory-constrained phones.
     */
    private suspend fun setBlindAidActiveFromTool(active: Boolean) {
        if (active && isLlmLoaded() && brainReleaseGuard()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { unloadLlmModel() }
                    .onSuccess { Logger.i("Orchestrator: unloaded Gemma before voice-started Blind Aid") }
                    .onFailure { Logger.e("Orchestrator: voice Blind Aid brain unload failed", it) }
            }
        }
        // The validated tool pipeline already narrates the action and final result. Suppress the
        // direct UI-toggle announcement here so a voice command is never spoken two or three times.
        setBlindAidActive(active, bringToForeground = active, announce = false)
    }

    /**
     * When blind aid is activated from a background path (VoiceService broadcast, FloatingAgent),
     * the CameraX preview in AgentScreen needs an active lifecycle to bind to.
     * This brings the app to the foreground so the Compose UI can start the camera.
     */
    private fun bringAppToForegroundIfNeeded() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
            Logger.i("Orchestrator: Launched MainActivity for blind-aid camera binding")
        } catch (e: Exception) {
            Logger.e("Orchestrator: Failed to launch MainActivity for blind-aid", e)
        }
    }

    suspend fun processCommand(text: String, inputType: InputType = InputType.TEXT) {
        if (!AgentRuntimeGate.isEnabled()) {
            Logger.i("Orchestrator: command rejected because UnoOne is disabled")
            return
        }
        // Atomic check-and-set to prevent concurrent command execution
        if (!processingLock.compareAndSet(false, true)) return
        _isProcessing.value = true
        _timelineSteps.value = emptyList()
        VoiceAgentRuntime.recordCommand(
            rawTranscript = text,
            language = currentVoiceLanguageCode(),
            preferredReplyLanguage = currentVoiceLanguageCode()
        )
        VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "command accepted")

        // C3: start a fresh run generation. A cancel stamps cancelledRunId with the latest run id;
        // checkpoints below compare the two so this run bails only if cancelled, and a stale cancel
        // from a previous run can't block this one.
        val myRun = currentRunId.incrementAndGet()

        // Eyes-free (WS2): remember this command's input type for step narration, and interrupt any
        // TTS still playing from the previous command so a new spoken command isn't talked over.
        currentInputType = inputType
        runCatching { voiceModule.stopSpeaking() }
        lastNarrationAt.set(0L)

        // SECURITY: Sanitize user input before processing
        var sanitizedText = InputSanitizer.sanitize(text)
        if (sanitizedText.isBlank()) {
            addStep(AgentStatus.FAILED, "Empty Input", "No command detected after sanitization.")
            releaseProcessingLock()
            return
        }

        // Voice language changes are deterministic and must work even while Gemma is unloaded.
        // Only explicit requests match; ordinary mentions of Hindi/English continue normally.
        VoiceLanguage.extractRequest(sanitizedText)?.let { request ->
            val switched = applyVoiceLanguageCommand(request.code, inputType)
            val remaining = InputSanitizer.sanitize(request.remainingCommand)
            if (!switched || remaining.isBlank()) {
                releaseProcessingLock()
                return
            }
            // Continue the same command through deterministic routing after the offline speech
            // engines switch. This makes "start blind mode and reply in Hindi" one operation
            // instead of discarding the requested action after changing the preference.
            sanitizedText = remaining
        }

        // A microphone check or greeting is a local protocol response, not an agent task. Keep this
        // ahead of brain self-healing, skills and planning so it remains instant even while Gemma is
        // unloaded or recovering.
        val fastReply = VoiceFastReply.replyFor(sanitizedText, currentVoiceLanguageCode())
        if (fastReply != null) {
            val startedAt = System.currentTimeMillis()
            addStep(AgentStatus.UNDERSTANDING, "Voice check", sanitizedText)
            addStep(AgentStatus.SPEAKING, "Response", fastReply)
            speakAnswer(fastReply)
            addStep(AgentStatus.DONE, "Done", fastReply)
            lastToolResult = fastReply
            saveLog(
                ActionLogEntity(
                    inputText = "[private voice check: ${sanitizedText.length} chars]",
                    inputType = inputType.name.lowercase(),
                    selectedTool = "voice_fast_reply",
                    status = "success",
                    modelLatencyMs = System.currentTimeMillis() - startedAt
                )
            )
            releaseProcessingLock()
            return
        }

        val startTime = System.currentTimeMillis()
        val log = ActionLogEntity(
            inputText = "[private command: ${sanitizedText.length} chars]",
            inputType = inputType.name.lowercase()
        )

        try {
            addStep(AgentStatus.UNDERSTANDING, "Understanding Command", sanitizedText)

            // C3: bail early if this run was cancelled while waiting for the lock.
            if (isCancelled(myRun)) { releaseProcessingLock(); return }

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
                if (steps.isEmpty()) {
                    addStep(AgentStatus.FAILED, "Invalid Skill", "This skill has no executable steps.")
                    saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "failed"))
                    releaseProcessingLock()
                    return
                }
                for (step in steps) {
                    addStep(AgentStatus.EXECUTING, "Skill Step", step)
                    val toolCall = commandParser.parse(step)
                    if (toolCall == null) {
                        addStep(
                            AgentStatus.FAILED,
                            "Invalid Skill Step",
                            "Could not understand: $step. Edit or disable this skill."
                        )
                        saveLog(log.copy(selectedTool = "skill:${skill.name}", status = "failed"))
                        releaseProcessingLock()
                        return
                    }
                    // Skills no longer bypass safety: each step runs the full pipeline
                    // (permissions → risk → block → confirm → execute → audit) just like a
                    // standalone command. On any NeedsAccess/Blocked/Cancelled we stop the skill.
                    val outcome = runValidatedToolCall(toolCall, step, learnUsage = false)
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
                            pendingRequiredPermission.set(outcome.missing.firstOrNull())
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

            // Step 1b: Intent routing — classify the command into a lane BEFORE planning.
            //
            // Simple conversational questions (CHAT) are answered in ONE inference on a dedicated
            // tool-less conversation: no agent planning, no safety/confirm gate (a tool-less answer
            // has nothing to gate), no ReAct loop, no screen/OCR context snapshot. Everything else —
            // a rule match (FAST_ACTION), an action order, or an ambiguous input (AGENT_ACTION /
            // UNKNOWN) — flows into the proven planning → safety → execute → ReAct path below.
            // UNKNOWN defaults into that path on purpose: a specific multi-step order the classifier
            // does not recognize is still caught by the agent pipeline (owner-endorsed: chats skip
            // the agent flow; specific orders do not).
            val ruleMatch = commandParser.parse(sanitizedText)
            val intent = IntentClassifier.classify(sanitizedText, ruleMatch)
            VoiceAgentRuntime.recordIntent(
                intent = ruleMatch?.tool ?: intent.name,
                confidence = if (ruleMatch != null) 1f else if (intent == IntentType.CHAT) .9f else .5f
            )
            Logger.i("Orchestrator: intent=$intent ruleMatch=${ruleMatch?.tool}")

            // Repair Gemma only when this command genuinely needs it. Reloading before
            // deterministic routing made simple actions (especially "stop blind mode" after Blind
            // Aid intentionally unloaded the brain) wait for model recovery and speak a late
            // "brain reloaded" message after the action had already completed.
            if (
                ruleMatch == null && SELF_HEAL_ENABLED &&
                lastLoadedPath != null && !isLlmLoaded()
            ) {
                selfHealReloadBrain()
            }
            if (intent == IntentType.CHAT) {
                if (isCancelled(myRun)) { releaseProcessingLock(); return }
                val chatStart = System.currentTimeMillis()
                addStep(AgentStatus.UNDERSTANDING, "Thinking", sanitizedText)
                val chatResult = if (commandParser.isModelLoaded()) {
                    commandParser.chat(sanitizedText)
                } else {
                    Result.Error("Local model unavailable")
                }
                if (isCancelled(myRun) || !AgentRuntimeGate.isEnabled()) {
                    releaseProcessingLock()
                    return
                }
                val answer = (chatResult as? Result.Success)?.data
                com.unoone.agent.observability.Diagnostics.recordStage("chat_inference", System.currentTimeMillis() - chatStart)
                if (!answer.isNullOrBlank()) {
                    addStep(AgentStatus.SPEAKING, "Response", answer)
                    speakAnswer(answer)
                    addStep(AgentStatus.DONE, "Done", answer)
                    lastToolResult = answer
                    saveLog(log.copy(
                        selectedTool = "chat",
                        status = "success",
                        modelLatencyMs = System.currentTimeMillis() - chatStart
                    ))
                    releaseProcessingLock()
                    return
                }
                // A conversational question must never be sent to tool extraction: that produced
                // the red "Extraction failed" shown for romanized Hindi. Preserve the question in
                // the UI and surface a recoverable local-model status without inventing an answer.
                val retryMessage =
                    "I couldn't answer that with the local model just now. Your question is still visible; please try again."
                Logger.w("Orchestrator: CHAT lane unavailable after local recovery attempt")
                addStep(AgentStatus.DONE, "Local model unavailable", retryMessage)
                if (inputType == InputType.VOICE) speakAnswer(retryMessage)
                saveLog(log.copy(
                    selectedTool = "chat",
                    status = "deferred",
                    errorMessage = "Local chat unavailable",
                    modelLatencyMs = System.currentTimeMillis() - chatStart
                ))
                releaseProcessingLock()
                return
            }

            // Step 2: Planning / Intent Extraction — delegate to CommandParser.
            // We ask for provenance (rule-based vs LLM) because the ReAct loop may only continue a
            // conversation that the LLM actually started — a rule match never opened one.
            //
            // Streaming: when enabled and the LLM path is taken, partial model text is surfaced to
            // the timeline as it streams (an evolving "Thinking" step). Any streaming failure
            // degrades gracefully to the synchronous provenance parse, so the device-verified
            // planning behavior is preserved. A rule match never reaches the LLM, so rule-handled
            // commands stream nothing — identical to before.
            val streamingBuffer = StringBuilder()
            var streamingStepAdded = false
            val planningStart = System.currentTimeMillis()
            if (isCancelled(myRun)) { releaseProcessingLock(); return }
            val parseOutcome = try {
                if (STREAMING_INFERENCE_ENABLED) {
                    commandParser.parseStreamingWithProvenance(sanitizedText, contextCommands, lastToolResult) { delta ->
                        // Add the "Thinking" step lazily on the first delta, so rule-handled commands
                        // (which short-circuit before the LLM) get no streaming step at all.
                        if (!streamingStepAdded) {
                            addStep(AgentStatus.UNDERSTANDING, "Thinking", delta)
                            streamingStepAdded = true
                            streamingBuffer.append(delta)
                        } else {
                            streamingBuffer.append(delta)
                            updateLastStepDetail(streamingBuffer.toString())
                        }
                    }
                } else {
                    commandParser.parseAsyncWithProvenance(sanitizedText, contextCommands, lastToolResult)
                }
            } catch (e: Exception) {
                Logger.w("Orchestrator: streaming plan unavailable, falling back to sync plan (${e.message})")
                commandParser.parseAsyncWithProvenance(sanitizedText, contextCommands, lastToolResult)
            }
            com.unoone.agent.observability.Diagnostics.recordStage("planning", System.currentTimeMillis() - planningStart)
            val toolCall = parseOutcome.toolCallOrNull()
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
            if (isCancelled(myRun)) { releaseProcessingLock(); return }
            val outcome = runValidatedToolCall(toolCall, sanitizedText)
            when (outcome) {
                is StepOutcome.NeedsSystemAccess -> {
                    pendingCommand.set(text)
                    pendingInputType.set(inputType)
                    pendingRequiredPermission.set(outcome.missing.firstOrNull())
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
                        val spokenFailure = VoiceResponseLocalizer.failure(currentVoiceLanguageCode())
                        addStep(AgentStatus.FAILED, "Action failed", result.message)
                        speakAnswer(spokenFailure)
                        saveLog(log.copy(selectedTool = toolCall.tool, status = "failed", errorMessage = result.message))
                        releaseProcessingLock()
                        return
                    }

                    // Step 6: Feedback & Verification
                    val observation = if (result is Result.Success) result.data.toString() else "Action completed."

                    // ReAct continuation: when the LLM (not the rule path) planned the first call AND
                    // the tool's result is something the model can reason over, feed the observation
                    // back and let the model propose the next step, bounded to MAX_STEPS. Every
                    // follow-up re-enters runValidatedToolCall, so the full safety pipeline
                    // (permissions → risk → block → confirm → execute → audit) applies to each
                    // continuation exactly as it does to the first call — safety is never bypassed.
                    // One-shot side-effect tools (open_app, create_note, …) skip the loop: the model
                    // has nothing to react to, so continuing would only add latency.
                    if (parseOutcome is ParseOutcome.Llm && ReActLoopController.shouldEngage(toolCall.tool)) {
                        if (isCancelled(myRun)) { releaseProcessingLock(); return }
                        addStep(AgentStatus.VERIFYING, "Agent Reasoning", "Reviewing result; planning next step…")
                        val finalSpoken = continueAgentLoop(
                            firstCall = toolCall,
                            firstObservation = observation,
                            sanitizedText = sanitizedText,
                            inputType = inputType,
                            log = log,
                            startTime = startTime
                        )
                        lastToolResult = finalSpoken
                        releaseProcessingLock()
                        return
                    }

                    // A speak_response is executed as data, then spoken here while holding the same
                    // serialization lock as milestone narration. This prevents reordered/overlapping
                    // fragments and keeps isProcessing true until playback has really completed.
                    if (toolCall.tool == "speak_response") {
                        addStep(AgentStatus.SPEAKING, "Response", observation)
                        speakAnswer(observation)
                        addStep(AgentStatus.DONE, "Done", observation)
                    } else {
                        val spokenObservation = VoiceResponseLocalizer.toolResult(
                            toolCall.tool,
                            observation,
                            currentVoiceLanguageCode()
                        )
                        addStep(AgentStatus.SPEAKING, "Response", spokenObservation)
                        speakAnswer(spokenObservation)
                        addStep(AgentStatus.DONE, "Done", spokenObservation)
                    }

                    saveLog(log.copy(
                        selectedTool = toolCall.tool,
                        toolArgsJson = toolCall.args.keys.sorted().joinToString(
                            prefix = "{\"privateArgKeys\":[\"",
                            separator = "\",\"",
                            postfix = "\"]}"
                        ),
                        status = "success",
                        modelLatencyMs = System.currentTimeMillis() - startTime
                    ))
                    lastToolResult = observation
                }
            }
        } catch (e: Exception) {
            Logger.e("Master Orchestrator Exception", e)
            addStep(AgentStatus.FAILED, "System Error", e.localizedMessage ?: "Error")
        } finally {
            // Single release point. releaseProcessingLock() already sets processingLock=false;
            // the extra set(false) was dead code that could clobber a concurrent command's lock
            // in the (suspension-free) window between an early return's release and this finally.
            // Command-to-completion latency for every lane (chat / rule / agent / error), recorded
            // here so no return path is missed. ActionLogEntity.modelLatencyMs is kept per-path for
            // log continuity; this is the diagnostics-aggregate total.
            com.unoone.agent.observability.Diagnostics.recordStage("command_total", System.currentTimeMillis() - startTime)
            releaseProcessingLock()
        }
    }

    fun clearPendingAndReExecute() {
        if (!AgentRuntimeGate.isEnabled()) {
            pendingCommand.set(null)
            pendingInputType.set(null)
            pendingRequiredPermission.set(null)
            return
        }
        val cmd = pendingCommand.getAndSet(null)
        val type = pendingInputType.getAndSet(null)
        val req = pendingRequiredPermission.getAndSet(null)
        if (cmd != null && type != null) {
            // C4: don't bounce back to system settings in a loop. If the system permission is STILL
            // not granted, re-running would just deep-link to settings again and re-stash the pending
            // command (the reported "keeps on saying reading screen, can't remove it" trap). Instead
            // speak a one-time clear instruction and stop. The user grants access and re-issues the
            // command, or taps Cancel.
            if (req != null && !PermissionManager.isRequirementSatisfied(context, req)) {
                Logger.i("Orchestrator: pending system permission '$req' still missing on resume — not re-running")
                scope.launch {
                    runCatching {
                        voiceModule.speakAwait(
                            "That needs an access you haven't enabled yet. " +
                                "Turn it on in Settings once, then ask me again — or say stop."
                        )
                    }
                }
                return
            }
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
                    pendingRequiredPermission.set(outcome.missing.firstOrNull())
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
                speakAnswer(responseText)
            }
        }
        saveLog(log.copy(
            selectedTool = "compound",
            status = if (combined is Result.Error) "failed" else "success",
            modelLatencyMs = System.currentTimeMillis() - startTime
        ))
    }

    /**
     * Bounded ReAct continuation. After the first LLM-planned, observation-producing tool executes,
     * feeds its result back to the model ([commandParser.planNext]) and lets the model propose the
     * next step, up to [ReActLoopController.MAX_STEPS] total. Every proposed step re-enters
     * [runValidatedToolCall], so the full safety pipeline (permissions → risk → block → confirm →
     * execute → audit) applies to follow-ups identically — safety is never bypassed, and nothing
     * the model proposes is executed directly (manual tool calling stays in force).
     *
     * The loop stops on: the model emitting `speak_response` (the natural end of a chain), no plan,
     * a planner error (unknown tool / malformed args / inference failure — rejected, never run), a
     * detected stall (the model re-proposes the identical call), the [ReActLoopController.MAX_STEPS]
     * ceiling, a blocked/cancelled/needs-access step (surfaced for re-execution like the first
     * call), or a step that fails to execute. Returns the final text spoken to the user.
     */
    private suspend fun continueAgentLoop(
        firstCall: ToolCall,
        firstObservation: String,
        sanitizedText: String,
        inputType: InputType,
        log: ActionLogEntity,
        startTime: Long
    ): String {
        var lastCall = firstCall
        var lastObservation = firstObservation
        var stepsExecuted = 1 // the first call already executed before the loop was entered.
        val toolsUsed = mutableListOf(firstCall.tool)

        while (true) {
            val proposal = commandParser.planNext(lastCall.tool, lastObservation)
            // Self-heal: an Error proposal means the brain became unreachable mid-loop (auto-closed on
            // timeout). Track consecutive inference failures and, once BrainHealthPolicy fires, attempt
            // a reload from the remembered path/spec — then stop this loop rather than spinning against
            // an unloaded model. A Success resets the streak.
            if (proposal is Result.Error) {
                consecutiveInferenceFailures++
                if (BrainHealthPolicy.shouldReload(consecutiveInferenceFailures) && !isLlmLoaded()) {
                    selfHealReloadBrain()
                    saveLog(log.copy(
                        selectedTool = toolsUsed.joinToString("→"),
                        status = "failed",
                        errorMessage = "agent brain reloaded mid-loop after inference failure"
                    ))
                    return lastObservation
                }
            } else {
                consecutiveInferenceFailures = 0
            }
            val decision = ReActLoopController.decide(stepsExecuted, lastCall, proposal)
            when (decision) {
                is LoopDecision.Continue -> {
                    addStep(AgentStatus.TOOL_SELECTED, "Agent Plan", "Follow-up: ${decision.call.tool}")
                    toolsUsed.add(decision.call.tool)
                    val outcome = runValidatedToolCall(decision.call, sanitizedText)
                    when (outcome) {
                        is StepOutcome.NeedsSystemAccess -> {
                            addStep(AgentStatus.FAILED, "Agent Paused", "Needs system access for ${decision.call.tool}")
                            onSystemPermissionRequired?.invoke(outcome.missing)
                            onSystemPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                            saveLog(log.copy(selectedTool = toolsUsed.joinToString("→"), status = "blocked"))
                            pendingCommand.set(sanitizedText)
                            pendingInputType.set(inputType)
                            pendingRequiredPermission.set(outcome.missing.firstOrNull())
                            return lastObservation
                        }
                        is StepOutcome.NeedsRuntimeAccess -> {
                            addStep(AgentStatus.FAILED, "Agent Paused", "Needs runtime permissions for ${decision.call.tool}")
                            pendingCommand.set(sanitizedText)
                            pendingInputType.set(inputType)
                            onPermissionRequired?.invoke(outcome.missing)
                            onPermissionRequiredMulticast.invokeAll { it(outcome.missing) }
                            saveLog(log.copy(selectedTool = toolsUsed.joinToString("→"), status = "blocked"))
                            return lastObservation
                        }
                        is StepOutcome.Blocked -> {
                            addStep(AgentStatus.FAILED, "Security Block", "Agent step '${decision.call.tool}' blocked for security.")
                            saveLog(log.copy(selectedTool = toolsUsed.joinToString("→"), status = "blocked"))
                            return lastObservation
                        }
                        is StepOutcome.Cancelled -> {
                            addStep(AgentStatus.FAILED, "Cancelled", "User declined agent confirmation")
                            saveLog(log.copy(selectedTool = toolsUsed.joinToString("→"), status = "cancelled"))
                            return lastObservation
                        }
                        is StepOutcome.Executed -> {
                            val execResult = outcome.result
                            if (execResult is Result.Error) {
                                addStep(AgentStatus.FAILED, "Execution Error", execResult.message)
                                saveLog(log.copy(
                                    selectedTool = toolsUsed.joinToString("→"),
                                    status = "failed",
                                    errorMessage = execResult.message
                                ))
                                return lastObservation
                            }
                            // Success → observe and let the controller decide whether to continue.
                            lastObservation = if (execResult is Result.Success) execResult.data.toString() else "Action completed."
                            lastCall = decision.call
                            stepsExecuted++
                        }
                    }
                }
                is LoopDecision.Stop -> {
                    val spoken = when (decision.reason) {
                        StopReason.SPOKE_RESPONSE -> decision.spokenText ?: lastObservation
                        StopReason.PLANNER_ERROR -> {
                            addStep(AgentStatus.FAILED, "Agent Halted", decision.plannerErrorText ?: "planner error")
                            lastObservation
                        }
                        StopReason.NO_PLAN -> {
                            addStep(AgentStatus.DONE, "Agent Halted", "No further plan.")
                            lastObservation
                        }
                        StopReason.STALL_DETECTED -> {
                            addStep(AgentStatus.DONE, "Agent Halted", "No new action proposed.")
                            lastObservation
                        }
                        StopReason.MAX_STEPS -> {
                            addStep(AgentStatus.DONE, "Agent Halted", "Reached step limit.")
                            lastObservation
                        }
                    }
                    if (inputType == InputType.VOICE) {
                        addStep(AgentStatus.SPEAKING, "Response", spoken)
                        speakAnswer(spoken)
                    } else {
                        addStep(AgentStatus.DONE, "Done", spoken)
                    }
                    saveLog(log.copy(
                        selectedTool = toolsUsed.joinToString("→"),
                        status = "success",
                        modelLatencyMs = System.currentTimeMillis() - startTime
                    ))
                    return spoken
                }
            }
        }
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
    private suspend fun runValidatedToolCall(
        toolCall: ToolCall,
        sanitizedText: String,
        learnUsage: Boolean = true
    ): StepOutcome {
        // 1. Non-runtime system access (Accessibility / Overlay / MediaProjection)
        val unsatisfiedSystem = safetyPipeline.unsatisfiedRequirements(toolCall.tool)
            .filterNot { it is PermissionRequirement.RuntimePerm }
        if (unsatisfiedSystem.isNotEmpty()) {
            VoiceAgentRuntime.transition(VoiceAgentState.ERROR_RECOVERY, "system access required")
            addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs system access for ${toolCall.tool}")
            return StepOutcome.NeedsSystemAccess(unsatisfiedSystem)
        }

        // 2. Runtime (dangerous) permissions
        val missingPermissions = safetyPipeline.checkPermissionsForTool(toolCall.tool)
        if (missingPermissions.isNotEmpty()) {
            VoiceAgentRuntime.transition(VoiceAgentState.ERROR_RECOVERY, "runtime permission required")
            addStep(AgentStatus.SAFETY_CHECK, "Access Required", "Needs permissions for ${toolCall.tool}")
            return StepOutcome.NeedsRuntimeAccess(missingPermissions)
        }

        // 3. Risk classification (tool risk + input risk, max wins)
        var riskLevel = safetyPipeline.classifyRisk(toolCall.tool, sanitizedText)

        // User-selected security posture (Settings → Security Level). Re-read per call so a change
        // in the app takes effect on the next command without a restart. See [SecurityLevel] for
        // the contract: STANDARD keeps the judge + BLOCK tier + confirmations; RELAXED drops the
        // judge and auto-approves confirmations but keeps the BLOCK tier; OFF drops the judge, the
        // BLOCK tier AND confirmations so every module can be exercised for a demo. The BLOCK-tier
        // tool names have no executor handlers, so OFF triggers no real payment/SMS/credential
        // side effect — it only removes the rejection message.
        val securityLevel = SecurityLevel.current(context)
        val judgeEnabled = securityLevel == SecurityLevel.STANDARD
        val blockEnforced = securityLevel != SecurityLevel.OFF
        val confirmationEnforced = securityLevel == SecurityLevel.STANDARD

        // 3b. LLM safety judge — a second on-device pass that catches paraphrased harm the keyword
        // filter misses (e.g. "wipe everything" → delete_all_notes). Only ever ESCALATES the tier
        // (see [SafetyJudgePolicy.escalate]); it can never weaken the keyword result. Skipped when
        // the brain is not loaded (offline / no model), the judge conversation is unavailable, OR
        // the user has lowered the security level below STANDARD (the judge's "when unsure, choose
        // the stricter verdict" bias is what hard-blocks benign commands like "add a calendar
        // event" via a false-positive UNSAFE — RELAXED/OFF turn that off). The keyword tier then
        // stands unchanged, so this never creates a safety hole. Gated by a flag so the per-step
        // latency cost of an extra inference can be turned off if needed.
        //
        // DIRECT tools are also skipped: the judge's value is catching paraphrased harm the keyword
        // tier UNDER-rates, and DIRECT is by definition the inert/launch tier (speak_response,
        // open_chrome, open_app, open_calendar, check_calendar, create_note, search_notes,
        // summarize_text, deactivate_blind_aid). Running a second inference + "stricter verdict"
        // bias on these is what produced the "speak_response → CONFIRM" confirmation popup for plain
        // answers. The keyword tier (SafetyGuard.classify + classifyFromInput) still classifies
        // them, so no safety hole is created; the judge still runs for every CONFIRM/STRONG_CONFIRM/
        // BLOCK tier where escalation matters.
        if (SafetyJudgePolicy.shouldRun(judgeEnabled, SAFETY_JUDGE_ENABLED, commandParser.isModelLoaded(), riskLevel)) {
            val judgeStart = System.currentTimeMillis()
            val verdict = commandParser.judgeSafety(toolCall.tool, toolCall.args.toString(), sanitizedText)
            com.unoone.agent.observability.Diagnostics.recordStage("safety_judge", System.currentTimeMillis() - judgeStart)
            if (verdict is Result.Success) {
                val judged = SafetyJudgePolicy.escalate(riskLevel, verdict.data)
                if (judged != riskLevel) {
                    addStep(AgentStatus.SAFETY_CHECK, "Safety Judge", "Escalated ${riskLevel.name} → ${judged.name}")
                    AuditLogger.log(toolCall.tool, judged, "escalated", sanitizedText)
                    riskLevel = judged
                }
            }
        }

        addStep(
            AgentStatus.SAFETY_CHECK, "Security Level",
            "security: ${securityLevel.name}"
        )
        addStep(AgentStatus.SAFETY_CHECK, "Safety Filter", "Risk: ${riskLevel.name}")

        if (blockEnforced && safetyPipeline.isBlocked(riskLevel)) {
            addStep(AgentStatus.FAILED, "Security Block", "Action blocked for security.")
            AuditLogger.log(toolCall.tool, riskLevel, "blocked", sanitizedText)
            return StepOutcome.Blocked(riskLevel)
        }

        if (confirmationEnforced && safetyPipeline.requiresConfirmation(riskLevel)) {
            val confirmationMessage = safetyPipeline.confirmationMessage(toolCall.tool, riskLevel)
            addStep(AgentStatus.SAFETY_CHECK, "Confirmation Required", confirmationMessage)
            VoiceAgentRuntime.transition(
                VoiceAgentState.WAITING_FOR_CONFIRMATION,
                "confirmation required for ${toolCall.tool}"
            )
            val confirmed = awaitConfirmation(confirmationMessage)
            if (!confirmed) {
                addStep(AgentStatus.FAILED, "Cancelled", "User declined confirmation")
                AuditLogger.log(toolCall.tool, riskLevel, "cancelled", sanitizedText)
                return StepOutcome.Cancelled
            }
        } else if (!confirmationEnforced && safetyPipeline.requiresConfirmation(riskLevel)) {
            // RELAXED / OFF: auto-approve the confirmation so the demo isn't blocked on a tap.
            addStep(
                AgentStatus.SAFETY_CHECK, "Auto-Confirmed",
                "Confirmation auto-approved (security: ${securityLevel.name})"
            )
        }

        // 4. Execute
        VoiceAgentRuntime.transition(VoiceAgentState.EXECUTING, "executing ${toolCall.tool}")
        addStep(AgentStatus.EXECUTING, "Agent Active", "Executing ${toolCall.tool}...")
        val execStart = System.currentTimeMillis()
        val result = actionExecutor.executeTool(toolCall)
        com.unoone.agent.observability.Diagnostics.recordToolExecution(
            toolCall.tool, System.currentTimeMillis() - execStart, result is Result.Success
        )
        // Outcome-learned memory: record this attempt so future similar requests can avoid a known-bad
        // tool and lean on a known-good one. Non-fatal — storeOutcome swallows Room failures so memory
        // can never break a command.
        try {
            memoryModule.storeOutcome(
                command = sanitizedText,
                tool = toolCall.tool,
                success = result is Result.Success,
                errorMessage = (result as? Result.Error)?.message
            )
        } catch (_: Exception) { }
        if (learnUsage && result is Result.Success) {
            try {
                val suggested = skillsModule.recordSuccessfulUse(sanitizedText, toolCall.tool)
                if (suggested != null) {
                    addStep(
                        AgentStatus.VERIFYING,
                        "Skill Suggested",
                        "${suggested.name.removePrefix(com.unoone.agent.skills.SkillLearningPolicy.SUGGESTION_PREFIX)} is ready for your review in Skills."
                    )
                }
            } catch (e: Exception) {
                Logger.w("Skills: usage learning failed safely: ${e.message}")
            }
        }
        // Self-heal: track per-tool health and surface a flaky tool once (e.g. an action that
        // consistently fails on this device) so the user knows it is unreliable. Pure decision in
        // ToolHealthTracker; this is the device-time wiring + audit record.
        if (SELF_HEAL_ENABLED) {
            toolHealthTracker.record(toolCall.tool, result is Result.Success)
            if (toolHealthTracker.isFlaky(toolCall.tool) && flaggedFlakyTools.add(toolCall.tool)) {
                addStep(AgentStatus.SAFETY_CHECK, "Flaky Tool",
                    "${toolCall.tool} has failed repeatedly — marked unreliable.")
                AuditLogger.log(toolCall.tool, RiskLevel.CONFIRM, "tool_marked_flaky", sanitizedText)
            } else if (result is Result.Success) {
                flaggedFlakyTools.remove(toolCall.tool) // recovered: clear the flag
            }
        }
        if (result is Result.Error) {
            VoiceAgentRuntime.recordError("TOOL_FAILED", result.message)
            VoiceAgentRuntime.transition(VoiceAgentState.ERROR_RECOVERY, "tool execution failed")
            addStep(AgentStatus.FAILED, "Execution Error", result.message)
        } else {
            VoiceAgentRuntime.recordOutcome(toolCall.tool, "executor reported success")
            VoiceAgentRuntime.transition(VoiceAgentState.VERIFYING, "verifying ${toolCall.tool}")
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
        // C3: don't re-populate the timeline after a cancel cleared it. The latest run is cancelled
        // when cancelledRunId >= currentRunId; a fresh run increments currentRunId past it.
        if (cancelledRunId.get() >= currentRunId.get()) return
        try {
            _timelineSteps.value = _timelineSteps.value + TimelineStep(status, label, detail)
        } catch (e: Exception) {
            Logger.e("Orchestrator: Failed to add timeline step", e)
        }
        narrateMilestone(status, label, detail)
    }

    /**
     * Eyes-free (WS2): speak a short milestone phrase for the given timeline step when the current
     * command is a voice command (or [narrateTextCommands] is on). Async + serialized via
     * [speakMutex] so it never blocks the pipeline nor overlaps the final answer. Throttled by
     * [NARRATION_MIN_INTERVAL_MS] to avoid cue-spam. The phrase selection lives in [NarrationPolicy]
     * (pure, JVM-tested); returns early when the policy says the step should stay silent.
     */
    private fun narrateMilestone(status: AgentStatus, label: String, detail: String) {
        if (currentInputType != InputType.VOICE && !narrateTextCommands) return
        val phrase = NarrationPolicy.narrationFor(status, label, detail) ?: return
        val localizedPhrase = VoiceResponseLocalizer.milestone(
            phrase,
            currentVoiceLanguageCode()
        )
        val now = System.currentTimeMillis()
        if (now - lastNarrationAt.get() < NARRATION_MIN_INTERVAL_MS) return
        lastNarrationAt.set(now)
        scope.launch {
            speakMutex.withLock {
                voiceModule.speakAwait(localizedPhrase)
                    .onError { msg: String, _: Throwable? -> Logger.w("Orchestrator: milestone narration failed: $msg") }
            }
        }
    }

    /**
     * Speak the final answer through the same serialized channel as milestone narration, so a queued
     * milestone drains before the answer plays (no overlap). Blocks the calling coroutine while
     * speaking — the timeline steps are already set, and the processing lock is released only after
     * the answer finishes, keeping the voice response atomic with respect to the next command.
     */
    private suspend fun speakAnswer(text: String) {
        if (text.isBlank()) return
        speakMutex.withLock {
            voiceModule.speakAwait(text)
                .onError { msg: String, _: Throwable? -> Logger.e("Orchestrator: answer speak failed: $msg") }
        }
    }

    /**
     * Updates the most recent timeline step's detail to [detail] (used to evolve the single
     * "Thinking" step as streamed LLM text arrives, instead of appending a new step per token).
     * If the timeline is empty, this is a no-op. Best-effort: never throws into the command path.
     */
    private fun updateLastStepDetail(detail: String) {
        try {
            val steps = _timelineSteps.value
            if (steps.isEmpty()) return
            val updated = steps.dropLast(1) + steps.last().copy(detail = detail)
            _timelineSteps.value = updated
        } catch (e: Exception) {
            Logger.w("Orchestrator: updateLastStepDetail failed (non-fatal): ${e.message}")
        }
    }

    private fun releaseProcessingLock() {
        _isProcessing.value = false
        processingLock.set(false)
        if (AgentRuntimeGate.isEnabled()) {
            VoiceAgentRuntime.transition(VoiceAgentState.WAKE_LISTENING, "command pipeline idle")
        }
    }

    /** C3: true when [myRun] has been cancelled by [cancelCurrentCommand]. */
    private fun isCancelled(myRun: Long): Boolean = cancelledRunId.get() >= myRun

    /**
     * C3: Cancel the in-flight command (if any) and clear the pending system-permission command.
     * Un-bricks the UI immediately — the blind user is never trapped in a "Reading screen" / stuck
     * processing state. Sets [cancelledRunId] to the latest run so the running [processCommand]
     * bails at its next checkpoint; releases the lock + clears the timeline + speaks "Stopped."
     * Safe to call when nothing is running (no-op besides clearing a stale pending command).
     */
    fun cancelCurrentCommand(speak: Boolean = true) {
        val wasActive = _isProcessing.value || pendingCommand.get() != null
        pendingCommand.set(null)
        pendingInputType.set(null)
        pendingRequiredPermission.set(null)
        cancelledRunId.set(currentRunId.get())
        _timelineSteps.value = emptyList()
        _isProcessing.value = false
        processingLock.set(false)
        VoiceAgentRuntime.transition(
            if (AgentRuntimeGate.isEnabled()) VoiceAgentState.WAKE_LISTENING
            else VoiceAgentState.DISABLED,
            "command cancelled"
        )
        if (wasActive && speak && AgentRuntimeGate.isEnabled()) {
            scope.launch {
                runCatching { voiceModule.speakAwait("Stopped.") }
                    .onFailure { Logger.w("Orchestrator: cancel speak failed: ${it.message}") }
            }
        }
    }

    /** Silent, non-recovering teardown used only by the persistent master disable control. */
    fun shutdownForDisable() {
        cancelCurrentCommand(speak = false)
        blindAidActivationInFlight.set(false)
        _isBlindAidActive.value = false
        runCatching { voiceModule.stopRecording() }
        runCatching { voiceModule.stopSpeaking() }
    }

    private suspend fun saveLog(log: ActionLogEntity) {
        try {
            withContext(Dispatchers.IO) { actionLogDao.insert(log) }
        } catch (e: Exception) { Logger.e("Log error", e) }
    }
}
