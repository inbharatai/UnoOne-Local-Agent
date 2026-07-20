package com.unoone.agent.execution

import android.content.Context
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.interfaces.IActionExecutor
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
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
     * LiteRT-LM `Content.ImageBytes`. Null by default → vision is inactive (the shipped Gemma 4 E2B
     * artifact is text-only), so `describe_scene` falls back to the always-available
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

    /**
     * describe_scene: produces a short, spoken scene description of the current screen. The
     * MediaProjection permission is already gated by the safety pipeline before this runs.
     *
     * Two paths, in priority order:
     *  1. Multimodal vision (device-time, INACTIVE with the shipped text-only models): when
     *     [_describeSceneWithVision] is wired by the orchestrator AND a vision-capable Gemma model
     *     is loaded, the screenshot bytes are described by LiteRT-LM `Content.ImageBytes`. On any
     *     Error (no vision weights, inference failure), this degrades to path 2 — never fails the
     *     tool solely because vision is unavailable.
     *  2. Always-available fallback: OCR text + foreground app/activity, framed by the JVM-tested
     *     [com.unoone.agent.core.agent.SceneDescriptionBuilder]. This is what runs today.
     *
     * Honesty: the fallback is a structured description from OCR + context, not true visual
     * understanding of objects/layout; it never fabricates screen content (the builder says "could
     * not read" when there are no signals). Vision understanding is pending a vision-capable
     * `.litertlm` artifact and a device matrix — see DEVICE_VERIFICATION.md.
     */
    private suspend fun describeScene(toolCall: ToolCall): Result<String> {
        if (!ScreenshotCapture.hasPermission()) {
            return Result.Error("Scene description requires MediaProjection permission. Grant it in Settings.")
        }
        val aspect = toolCall.args["aspect"]?.jsonPrimitive?.content ?: ""

        // Path 1: multimodal vision, if wired. Best-effort; any failure falls through to the
        // always-available OCR + context description.
        val vision = _describeSceneWithVision
        if (vision != null) {
            val bitmap = (screenshotCapture.captureScreen() as? Result.Success)?.data
            if (bitmap != null) {
                val bytes = bitmapToJpeg(bitmap)
                if (bytes != null) {
                    try {
                        val v = vision(bytes, aspect)
                        if (v is Result.Success && v.data.isNotBlank()) return Result.Success(v.data)
                    } catch (e: Exception) {
                        Logger.w("describe_scene: vision path failed, using OCR fallback (${e.message})")
                    }
                }
            }
        }

        // Path 2: OCR + foreground context → SceneDescriptionBuilder.
        val contextStr = try { accessibilityControl.getCurrentContext() ?: "" } catch (_: Exception) { "" }
        val pkg = contextStr.substringBefore("/").ifBlank { "" }
        val activity = contextStr.substringAfter("/", "").ifBlank { "" }
        val ocrText = try {
            (ocrControl.recognizeScreen() as? Result.Success)?.data ?: ""
        } catch (_: Exception) { "" }
        val description = com.unoone.agent.core.agent.SceneDescriptionBuilder.build(
            com.unoone.agent.core.agent.SceneInput(
                currentPackage = pkg,
                currentActivity = activity,
                ocrText = ocrText,
                aspect = aspect
            )
        )
        return Result.Success(description)
    }

    /** Encodes a screenshot Bitmap to JPEG bytes for the LiteRT-LM `Content.ImageBytes` vision path. */
    private fun bitmapToJpeg(bitmap: android.graphics.Bitmap): ByteArray? = try {
        val baos = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        baos.toByteArray()
    } catch (e: Exception) {
        Logger.w("describe_scene: bitmap encode failed (${e.message})")
        null
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
