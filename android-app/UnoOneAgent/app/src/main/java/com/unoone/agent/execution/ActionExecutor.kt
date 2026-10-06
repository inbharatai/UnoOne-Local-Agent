package com.unoone.agent.execution

import android.content.Context
import com.unoone.agent.core.device.sensitiveObservation
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.interfaces.IActionExecutor
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.core.model.ToolCallValidator
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.safety.ToolPermissionRegistry
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.util.TextSummarizer
import com.unoone.agent.data.DataExporter
import com.unoone.agent.phonecontrol.CalendarControl
import com.unoone.agent.phonecontrol.LaunchAttempt
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
import kotlinx.coroutines.delay

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
    /** Own screenshot capturer for the `describe_scene` vision path (shares the static MediaProjection). */
    private val screenshotCapture = ScreenshotCapture(context)

    override suspend fun executeTool(toolCall: ToolCall): Result<String> {
        val normalized = try { ToolCallValidator.adaptLegacySkill(toolCall) } catch (e: IllegalArgumentException) {
            return Result.Error(e.message ?: "Invalid legacy skill")
        }
        ToolCallValidator.rejection(normalized)?.let { return Result.Error(it) }
        // Runtime already owns UI. Never reacquire from an inherited dispatcher/child Job.
        val execution = com.unoone.agent.task.ResourceEffects.execution()
        require(com.unoone.agent.task.TaskToolAuthorization.handle(normalized) in execution.context.scope.objectHandles) { "Tool outside native task scope" }
        execution.beforeEffect(com.unoone.agent.task.NativeToolEffects.capability(normalized.tool))
        return executeValidatedTool(normalized)
    }

    private suspend fun executeValidatedTool(toolCall: ToolCall): Result<String> {
        if (!AgentRuntimeGate.isEnabled()) {
            return Result.Error("UnoOne is disabled. Enable it before running an action.")
        }
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
                    val stepsList = (toolCall.args.getValue("steps") as JsonArray).map { it.jsonPrimitive.content }
                    val module = _skillsModule
                        ?: return Result.Error("Skills module not available")
                    if (stepsList.isEmpty()) {
                        return Result.Error("A skill needs at least one executable step.")
                    }
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
                    verifyForegroundLaunch(
                        phoneControl.draftEmail(to, sub, body),
                        actionLabel = "Email",
                        successMessage = "Email draft opened. Please review and press send."
                    )
                }
                "send_whatsapp" -> {
                    val number = toolCall.args["number"]?.jsonPrimitive?.content ?: ""
                    val msg = toolCall.args["message"]?.jsonPrimitive?.content ?: ""
                    verifyForegroundLaunch(
                        phoneControl.sendWhatsAppMessage(number, msg),
                        actionLabel = "WhatsApp",
                        successMessage = "WhatsApp draft opened. Please review and press send."
                    )
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
                    val start = parseTimeMs(toolCall.args["start_time"]?.jsonPrimitive?.content)
                        ?: return Result.Error(
                            "Calendar date or time is missing or ambiguous. Please give an exact date and time."
                        )
                    val end = parseTimeMs(toolCall.args["end_time"]?.jsonPrimitive?.content) ?: (start + 3_600_000L)
                    verifyForegroundLaunch(
                        phoneControl.openCalendarInsert(title, start, end),
                        actionLabel = "Calendar",
                        successMessage = "Calendar insert opened for '$title'."
                    )
                }
                "open_calendar" -> {
                    verifyForegroundLaunch(
                        phoneControl.openCalendar(),
                        actionLabel = "Calendar",
                        successMessage = "Calendar opened."
                    )
                }
                "open_app" -> {
                    val appName = toolCall.args["app_name"]?.jsonPrimitive?.content ?: ""
                    val pkg = toolCall.args["package_name"]?.jsonPrimitive?.content
                        ?: PackageResolver.resolveAppName(appName)
                        ?: return Result.Error("Could not resolve app '$appName'. Provide package_name.")
                    val actionLabel = appName.ifBlank { pkg }
                    verifyForegroundLaunch(
                        phoneControl.openApp(pkg),
                        actionLabel = actionLabel,
                        successMessage = "Opened $actionLabel."
                    )
                }
                "open_url" -> {
                    val url = toolCall.args["url"]?.jsonPrimitive?.content ?: ""
                    if (url.isBlank()) Result.Error("open_url requires a url")
                    else phoneControl.openUrl(url).map { "Opened $url." }
                }
                "prepare_document_fill" -> {
                    val format = toolCall.args["format"]?.jsonPrimitive?.content?.lowercase() ?: "pdf"
                    if (format !in setOf("pdf", "docx")) {
                        Result.Error("Document format must be pdf or docx")
                    } else {
                        val opener = _prepareDocumentFill
                            ?: return Result.Error("Document Agent is not available right now")
                        opener(format)
                        Result.Success("Opening the offline ${format.uppercase()} document picker.")
                    }
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
                "describe_scene" -> describeScene(toolCall)
                "detect_objects" -> {
                    _setBlindAidActive?.invoke(true)
                    Result.Success("Blind Aid activated.")
                }
                "deactivate_blind_aid" -> {
                    _setBlindAidActive?.invoke(false)
                    Result.Success("Blind Aid deactivated.")
                }
                "voice_recording" -> {
                    // Record a short memo via the shared VoiceModule, transcribe it offline with
                    // Sherpa STT, and persist the transcription as a note. RECORD_AUDIO is gated
                    // by the safety pipeline before this branch runs.
                    val duration = (toolCall.args["duration_seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5)
                        .coerceIn(1, 30)
                    val title = toolCall.args["title"]?.jsonPrimitive?.content
                    val recorder = _recordVoiceNote
                        ?: return Result.Error("Voice recording is not available (no voice module).")
                    when (val res = recorder(duration)) {
                        is Result.Success -> {
                            val content = res.data.trim()
                            if (content.isBlank()) Result.Error("Voice memo was empty — nothing transcribed.")
                            else {
                                val noteTitle = title?.takeIf { it.isNotBlank() } ?: content.take(40)
                                noteDao.insert(NoteEntity(title = noteTitle, content = content, tags = "voice"))
                                Result.Success("Voice memo saved: $content")
                            }
                        }
                        is Result.Error -> Result.Error("Voice recording failed: ${res.message}")
                    }
                }
                "web_search" -> {
                    // Opt-in, safety-gated online lookup via RAGManager's DuckDuckGo HTML scrape.
                    // Offline-first: returns an explicit offline message when there is no network,
                    // and never auto-opens links. Snippets are returned as text for the agent to speak.
                    val query = toolCall.args["query"]?.jsonPrimitive?.content ?: ""
                    if (query.isBlank()) Result.Error("web_search requires a query")
                    else if (!isOnline()) Result.Success("Offline — web search is unavailable. Connect to the internet and try again.")
                    else {
                        val snippets = com.unoone.agent.localbrain.RAGManager.fetchOnlineContext(query)
                        if (snippets.isBlank()) Result.Success("No web results found for '$query'.")
                        else Result.Success("Web results for '$query':\n$snippets")
                    }
                }
                "secure_browser_task" -> {
                    // Eyes-free (WS4): drive the Secure Browser (local Page Agent on a hardened
                    // WebView) to an APPROVED origin and run a task. The origin is resolved + approved
                    // gated BEFORE the session opens, so the model cannot drive an arbitrary site.
                    // In-browser sensitivity (passwords/OTP/payments/legal) stays gated by the
                    // BrowserSafetyPolicy per-action confirm/takeover inside the session.
                    // A blank task means "navigate to the approved origin only" (no PageAgent run);
                    // the model is told `task` is required, but the rule-based parser may emit a blank
                    // task for a bare "open unigurus".
                    val originRaw = toolCall.args["origin"]?.jsonPrimitive?.content ?: ""
                    val task = toolCall.args["task"]?.jsonPrimitive?.content ?: ""
                    val prototypeMode = com.unoone.agent.safety.SecurityLevel.current(context) ==
                        com.unoone.agent.safety.SecurityLevel.OFF
                    val origin = if (prototypeMode) {
                        com.unoone.agent.securebrowser.ApprovedOriginPolicy.prototypeUrlFor(originRaw)
                    } else {
                        com.unoone.agent.securebrowser.ApprovedOriginPolicy.originFor(originRaw)
                    }
                    if (origin == null) {
                        Result.Error(
                            if (prototypeMode) {
                                "Target '$originRaw' is not a valid public HTTPS page."
                            } else {
                                "Origin '$originRaw' is not approved for UnoOne automation. " +
                                    "Approved origins: unigurus, uniassist, testsprep, inbharat."
                            }
                        )
                    } else {
                        val runner = _openSecureBrowserTask
                            ?: return Result.Error(
                                "Secure Browser is not available right now. Open it from the main page first."
                            )
                        runner(origin, task)
                    }
                }
                // "compound" is expanded into ordered sub-calls by AgentOrchestrator and never
                // reaches executeTool; unknown tools are rejected rather than routed around validation.
                else -> Result.Error("Unknown tool: ${toolCall.tool}")
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

    /**
     * `Context.startActivity()` returning normally means Android accepted an intent, not that the
     * target reached the foreground. Poll the AccessibilityService's exact package observation
     * before returning success. Without Accessibility, report the launch as unverified rather than
     * speaking a false success.
     */
    private suspend fun verifyForegroundLaunch(
        launchResult: Result<LaunchAttempt>,
        actionLabel: String,
        successMessage: String
    ): Result<String> {
        val attempt = when (launchResult) {
            is Result.Success -> launchResult.data
            is Result.Error -> return launchResult
        }
        if (!accessibilityControl.isServiceEnabled()) {
            return Result.Error(ForegroundLaunchVerifier.unavailableMessage(actionLabel))
        }
        repeat(FOREGROUND_VERIFICATION_ATTEMPTS) {
            if (ForegroundLaunchVerifier.matches(attempt, accessibilityControl.getCurrentPackage())) {
                return Result.Success(successMessage)
            }
            delay(FOREGROUND_VERIFICATION_INTERVAL_MS)
        }
        return Result.Error(ForegroundLaunchVerifier.mismatchMessage(actionLabel))
    }

    // Injected callbacks — set by Orchestrator to avoid circular dependencies
    var _skillsModule: com.unoone.agent.skills.SkillsModule? = null
    var _setBlindAidActive: (suspend (Boolean) -> Unit)? = null
    /**
     * Record a voice memo for [durationSeconds] and return the offline STT transcription.
     * Set by the Orchestrator, which owns the shared VoiceModule + coroutine scope. The
     * RECORD_AUDIO runtime permission is checked by the safety pipeline before this runs.
     */
    var _recordVoiceNote: (suspend (durationSeconds: Int) -> Result<String>)? = null
    /**
     * Optional multimodal-vision path for `describe_scene`: when set AND a vision-capable Gemma
     * model is loaded, the orchestrator supplies a callback that describes a screenshot image via
     * LiteRT-LM `Content.ImageBytes`. Null by default → vision is inactive in this app configuration,
     * not absent from the upstream multimodal E4B artifact. `describe_scene` falls back to the
     * OCR + foreground-context description built by [com.unoone.agent.core.agent.SceneDescriptionBuilder].
     * Device-time-only; not exercised by unit tests.
     */
    var _describeSceneWithVision: (suspend (imageBytes: ByteArray, aspect: String) -> Result<String>)? = null
    /**
     * Eyes-free (WS4): bridge the `secure_browser_task` tool to the UI-owned Secure Browser session.
     * Set by the Orchestrator, which exposes it to MainActivity (the owner of the SecureBrowserViewModel
     * + nav controller). Receives the already-approved canonical origin + the task; returns the
     * tool result string. When null (Secure Browser screen not reachable in this build / not wired),
     * the tool returns a handled "not available" Result.Error — never a router fallback and never a
     * fake success. The live handoff (navigate, acquire the Gemma lease, run the PageAgent task) is a
     * device-time gate; the callback only fires the UI request and reports an acknowledgement.
     */
    var _openSecureBrowserTask: ((origin: String, task: String) -> Result<String>)? = null
    /** Opens the UI-owned Document Agent picker; the user still chooses input and output files. */
    var _prepareDocumentFill: ((format: String) -> Unit)? = null

    /**
     * True when the device has an active internet connection. Used by [web_search] so the
     * offline-first agent answers immediately instead of waiting on a 5s socket timeout when
     * the user is offline. minSdk 28 → `activeNetwork` / `getNetworkCapabilities` are available.
     */
    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false
    }

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

    private companion object {
        const val FOREGROUND_VERIFICATION_ATTEMPTS = 20
        const val FOREGROUND_VERIFICATION_INTERVAL_MS = 125L
    }

    /**
     * read_screen: reads on-screen text via the Accessibility tree only. The pre-execution gate
     * already required Accessibility; we do NOT prompt for MediaProjection mid-execution — that
     * would request access the permission registry never declared for this tool.
     */
    /** Immutable metadata identity; obtaining it never reads node text. */
    private data class ReadIdentity(val pkg: String, val window: Int, val bounds: android.graphics.Rect,
        val width: Int, val height: Int, val sequence: Long)

    private fun readIdentity(packages: Set<String>): ReadIdentity {
        val service = checkNotNull(com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance())
        val sequence = service.eventSequence
        val root = checkNotNull(service.rootInActiveWindow) { "No active window" }
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            check(pkg.isNotEmpty() && pkg in packages) { "Screen outside admitted package scope" }
            val bounds = android.graphics.Rect().also(root::getBoundsInScreen)
            val metrics = service.resources.displayMetrics
            check(sequence == service.eventSequence) { "Screen changed" }
            ReadIdentity(pkg, root.windowId, bounds, metrics.widthPixels, metrics.heightPixels, sequence)
        } finally { root.recycle() }
    }

    private suspend fun <T> scopedRead(block: suspend (ReadIdentity, () -> Unit) -> T): T {
        val execution = com.unoone.agent.task.ResourceEffects.execution()
        val packages = execution.context.scope.packages.toSet()
        val identity = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { readIdentity(packages) }
        val boundary = com.unoone.agent.core.task.NativeReadBoundary(packages,
            { readIdentity(packages).let { it.pkg to it } }, execution::checkActive)
        check(identity == readIdentity(packages)) { "Screen changed before read" }
        return boundary.read { verify -> block(identity, verify) }
    }

    private suspend fun readScreenWithAccessibility(): Result<String> = scopedRead { identity, verify ->
        val service = checkNotNull(com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance())
        val adapter = com.unoone.agent.accessibilitycontrol.AndroidDeviceAdapter(service,
            allowedObservationPackages = setOf(identity.pkg))
        verify()
        val snapshot = adapter.observe().snapshot
        verify()
        val text = com.unoone.agent.core.device.SensitiveReadRedaction.readScreen(snapshot, identity.pkg)
        if (text.isBlank()) Result.Error("No readable text on screen") else Result.Success(text)
    }

    private suspend fun readScreenWithOcr(): Result<String> = scopedRead { identity, verify ->
        if (!ScreenshotCapture.hasPermission()) return@scopedRead Result.Error("Screenshot permission not granted")
        // Full-display pixels are unsafe for split windows/overlays. Require one full-size active
        // application window; source redaction remains mandatory even on this narrow path.
        check(identity.bounds == android.graphics.Rect(0, 0, identity.width, identity.height)) { "Ambiguous screen geometry" }
        val service = checkNotNull(com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance())
        fun verifyPixelWindows() {
            verify()
            check(com.unoone.agent.core.task.PixelWindowPolicy.allows(
                service.windows.map { it.id to it.type }, identity.window,
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)) {
                "Overlay or ambiguous display pixels; close overlays or review a capture manually"
            }
        }
        verifyPixelWindows()
        val snapshot = com.unoone.agent.accessibilitycontrol.AndroidDeviceAdapter(service,
            allowedObservationPackages = setOf(identity.pkg)).observe().snapshot
        check(!snapshot.truncated && snapshot.nodes.isNotEmpty() && snapshot.nodes.none {
            it.password || it.semantic.sensitiveObservation()
        }) { "Sensitive or unverifiable screen; read manually" }
        verifyPixelWindows()
        when (val capture = screenshotCapture.captureScreen()) {
            is Result.Error -> Result.Error(capture.message)
            is Result.Success -> try {
                verifyPixelWindows()
                check(capture.data.width == identity.width && capture.data.height == identity.height) { "Capture geometry changed" }
                val result = ocrControl.recognizeText(capture.data)
                verifyPixelWindows()
                result
            } finally { capture.data.recycle() }
        }
    }

    private suspend fun describeScene(toolCall: ToolCall): Result<String> = scopedRead { identity, verify ->
        // Raw pixels must not enter a model before source redaction. Use guarded OCR only.
        val ocr = readScreenWithOcr()
        verify()
        when (ocr) {
            is Result.Error -> ocr
            is Result.Success -> Result.Success(com.unoone.agent.core.agent.SceneDescriptionBuilder.build(
                com.unoone.agent.core.agent.SceneInput(currentPackage = identity.pkg, currentActivity = "",
                    ocrText = ocr.data, aspect = toolCall.args["aspect"]?.jsonPrimitive?.content ?: "")))
        }
    }

    private suspend fun executeSystemAction(toolCall: ToolCall): Result<String> {
        val action = toolCall.args["action"]?.jsonPrimitive?.content ?: ""
        return when (action) {
            "scroll_down" -> accessibilityControl.scrollDown().map { "Scrolled down" }
            "scroll_up" -> accessibilityControl.scrollUp().map { "Scrolled up" }
            "go_back" -> accessibilityControl.goBack().map { "Went back" }
            "go_home" -> accessibilityControl.goHome().map { "Went home" }
            "open_notifications" -> accessibilityControl.openNotifications().map { "Opened notifications" }
            "open_recents" -> accessibilityControl.openRecents().map { "Opened recents" }
            else -> Result.Error("Unknown system action: $action")
        }
    }

    private fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }
}
