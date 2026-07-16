package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.browser.SecureBrowserModelLease
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.onError
import com.unoone.agent.securebrowser.BrowserActionClass
import com.unoone.agent.securebrowser.BrowserAuditEvent
import com.unoone.agent.securebrowser.BrowserDomainPolicy
import com.unoone.agent.securebrowser.BrowserNavigationMode
import com.unoone.agent.securebrowser.BrowserEventSink
import com.unoone.agent.securebrowser.BrowserUserInteraction
import com.unoone.agent.securebrowser.BrowserSafetyMode
import com.unoone.agent.securebrowser.PageAgentRequestType
import com.unoone.agent.securebrowser.SecureBrowserNativeHandler
import com.unoone.agent.securebrowser.SecureWebViewController
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.entity.ActionLogEntity
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.safety.SecurityLevel
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID


enum class BrowserPromptKind { CONFIRM, ASK, TAKEOVER }

data class BrowserPrompt(
    val id: String = UUID.randomUUID().toString(),
    val kind: BrowserPromptKind,
    val message: String
)

data class SecureBrowserUiState(
    val phase: String = "Idle",
    val status: String = "Secure Browser is not started",
    val currentUrl: String = "",
    val runtimeReady: Boolean = false,
    val sessionActive: Boolean = false,
    val taskRunning: Boolean = false,
    val modelBackend: String = "",
    val prototypeSafetyOff: Boolean = false,
    val lastResult: String = "",
    val error: String = ""
)

private data class PromptAnswer(val approved: Boolean, val text: String)

/**
 * Owns one UnoOne Secure Browser session and its human-in-the-loop prompts.
 * Raw page content, model prompts and typed form values are never persisted by this ViewModel.
 */
class SecureBrowserViewModel(
    context: Context,
    private val modelLease: SecureBrowserModelLease,
    private val actionLogDao: ActionLogDao,
    private val voiceModule: VoiceModule? = null
) : ViewModel(), BrowserUserInteraction {

    private val appContext = context.applicationContext
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val promptMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    private val domainPolicy = BrowserDomainPolicy(APPROVED_ORIGINS)
    private var controller: SecureWebViewController? = null
    private var pendingPrompt: CompletableDeferred<PromptAnswer>? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var attached = false

    // Eyes-free (WS4): a (origin, task) stashed by the `secure_browser_task` tool before the
    // Secure Browser screen is composed. When the runtime becomes ready we auto-navigate to the
    // origin (if different) and run the task. A blank task means navigate-only.
    @Volatile private var pendingOrigin: String? = null
    @Volatile private var pendingTask: String? = null
    // Last spoken narration, to avoid repeating the identical status string verbatim.
    @Volatile private var lastNarration: String = ""

    private val _state = MutableStateFlow(SecureBrowserUiState())
    val state: StateFlow<SecureBrowserUiState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<BrowserPrompt?>(null)
    val prompt: StateFlow<BrowserPrompt?> = _prompt.asStateFlow()

    // Eyes-free (WS4): voice-driven task input on the browser screen. A blind user taps the mic,
    // speaks a task, and PageAgent runs it — no typing. Mirrors AgentViewModel's listen pattern.
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun attachWebView(webView: WebView) {
        if (attached) return
        attached = true

        if (!runtimeAssetExists()) {
            _state.value = _state.value.copy(
                phase = "Not built",
                status = "Alibaba PageAgent runtime bundle is missing",
                error = "Run npm run bundle:android in web-runtime/page-agent-unoone before building the APK."
            )
            return
        }

        _state.value = _state.value.copy(
            phase = "Starting",
            status = "Reserving Gemma 4 for Secure Browser…",
            error = ""
        )
        viewModelScope.launch {
            when (val leaseResult = modelLease.acquire()) {
                is Result.Error -> {
                    _state.value = _state.value.copy(
                        phase = "Unavailable",
                        status = "Secure Browser could not start",
                        error = leaseResult.message
                    )
                }
                is Result.Success -> {
                    val handler = SecureBrowserNativeHandler(
                        modelPort = leaseResult.data,
                        userInteraction = this@SecureBrowserViewModel,
                        eventSink = BrowserEventSink { type, payload -> onNativeEvent(type, payload) },
                        safetyModeProvider = {
                            if (SecurityLevel.current(appContext) == SecurityLevel.OFF) {
                                BrowserSafetyMode.PROTOTYPE_OFF
                            } else {
                                BrowserSafetyMode.STANDARD
                            }
                        }
                    )
                    withContext(Dispatchers.Main.immediate) {
                        val prototypeMode = SecurityLevel.current(appContext) == SecurityLevel.OFF
                        controller = SecureWebViewController(
                            context = appContext,
                            webView = webView,
                            domainPolicy = domainPolicy,
                            navigationMode = if (prototypeMode) {
                                BrowserNavigationMode.PROTOTYPE_PUBLIC_HTTPS
                            } else {
                                BrowserNavigationMode.APPROVED_ONLY
                            },
                            scope = viewModelScope,
                            requestHandler = handler,
                            onBlockedNavigation = { reason ->
                                _state.value = _state.value.copy(status = "Navigation blocked", error = reason)
                            },
                            onRuntimeReady = {
                                _state.value = _state.value.copy(
                                    phase = "Ready",
                                    status = if (_state.value.currentUrl.isBlank()) {
                                        "PageAgent ready — enter a URL or load an offline form"
                                    } else if (prototypeMode) {
                                        "PageAgent ready on public HTTPS page"
                                    } else {
                                        "PageAgent ready on approved page"
                                    },
                                    runtimeReady = true,
                                    sessionActive = true,
                                    modelBackend = modelLease.activeBackend(),
                                    prototypeSafetyOff = prototypeMode,
                                    error = ""
                                )
                                runPendingTaskIfAny()
                            },
                            onRuntimeError = { message ->
                                _state.value = _state.value.copy(
                                    phase = "Runtime error",
                                    status = "PageAgent failed to initialize",
                                    runtimeReady = false,
                                    error = message
                                )
                            },
                            onShowFileChooser = ::openFileChooser
                        ).also { browser ->
                            val requestedUrl = _state.value.currentUrl
                            if (requestedUrl.isBlank()) {
                                browser.loadLocalHtml(WELCOME_PAGE_HTML, "Page Agent Home")
                            } else {
                                browser.load(requestedUrl)
                            }
                        }
                    }
                }
            }
        }
    }

    fun navigate(rawUrl: String) {
        val entered = rawUrl.trim()
        if (entered.isBlank()) return
        val clean = if (entered.contains("://")) entered else "https://$entered"
        val prototypeMode = SecurityLevel.current(appContext) == SecurityLevel.OFF
        _state.value = _state.value.copy(
            currentUrl = clean,
            status = if (prototypeMode) "Opening public HTTPS page…" else "Opening approved page…",
            error = ""
        )
        controller?.load(clean)
    }

    /** Registers the Activity Result launcher owned by the Compose screen. */
    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
        if (launcher == null) {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

    /** Returns the Android picker result to the exact WebView file input that requested it. */
    fun completeFileChooser(resultCode: Int, data: Intent?) {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
    }

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        val launcher = fileChooserLauncher ?: return false
        // WebView permits only one outstanding callback. Cancel the older request explicitly so a
        // page cannot leak it by opening a second picker before the first one returns.
        pendingFileCallback?.onReceiveValue(null)
        return try {
            pendingFileCallback = callback
            launcher(params.createIntent())
            true
        } catch (e: Exception) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            _state.value = _state.value.copy(error = "Could not open the file picker: ${e.message}")
            false
        }
    }

    /**
     * C9: load a local/offline HTML form (e.g. a user-picked .html file) into the sandboxed WebView
     * at the synthetic local-form origin, then inject the PageAgent runtime so the agent can work the
     * form offline. Every action the agent plans still round-trips through AUTHORIZE_ACTION →
     * BrowserSafetyPolicy (origin-agnostic) — payment/credential/OTP/captcha/legal/final-submission
     * gates apply unchanged. No safety gate is weakened; the only addition is admitting the synthetic
     * origin, which is reachable solely via [SecureWebViewController.loadLocalHtml].
     */
    fun loadLocalFormHtml(html: String, displayName: String) {
        if (html.isBlank()) {
            _state.value = _state.value.copy(error = "The selected form is empty")
            narrate("The selected form is empty.")
            return
        }
        val ctrl = controller
        if (ctrl == null) {
            _state.value = _state.value.copy(error = "Secure Browser isn't ready yet")
            narrate("The secure browser isn't ready yet.")
            return
        }
        _state.value = _state.value.copy(
            phase = "Local form",
            status = "Loading local form: $displayName",
            currentUrl = SecureWebViewController.LOCAL_FORM_ORIGIN,
            error = ""
        )
        narrate("Loaded local form: $displayName. Say or type what you'd like filled in.")
        ctrl.loadLocalHtml(html, displayName)
    }

    fun executeTask(task: String) {
        if (_state.value.taskRunning) return
        if (!_state.value.runtimeReady) {
            _state.value = _state.value.copy(error = "PageAgent is not ready on the current page")
            return
        }
        _state.value = _state.value.copy(
            taskRunning = true,
            phase = "Running",
            status = "PageAgent is working…",
            lastResult = "",
            error = ""
        )
        controller?.executeTask(task) { success, result ->
            _state.value = _state.value.copy(
                taskRunning = false,
                phase = if (success) "Completed" else "Failed",
                status = if (success) "Browser task completed" else "Browser task failed",
                lastResult = result.take(2_000),
                error = if (success) "" else result.take(1_000)
            )
            // Eyes-free narration of the terminal task result.
            narrate(if (success) "Done. $result" else "Browser task failed. $result")
        }
    }

    fun stopTask() {
        controller?.stopTask()
        _state.value = _state.value.copy(
            taskRunning = false,
            phase = "Stopped",
            status = "Browser task stopped"
        )
    }

    fun goBack(): Boolean {
        val current = controller ?: return false
        return if (current.canGoBack()) {
            current.goBack()
            true
        } else false
    }

    fun closeSession() {
        if (!attached) return
        attached = false
        controller?.stop()
        controller = null
        pendingPrompt?.cancel()
        pendingPrompt = null
        _prompt.value = null
        _state.value = _state.value.copy(
            phase = "Closing",
            status = "Restoring UnoOne phone brain…",
            runtimeReady = false,
            sessionActive = false,
            taskRunning = false
        )
        cleanupScope.launch {
            val result = modelLease.release(restore = true)
            _state.value = when (result) {
                is Result.Success -> _state.value.copy(phase = "Closed", status = "Secure Browser closed")
                is Result.Error -> _state.value.copy(
                    phase = "Closed with error",
                    status = "Secure Browser closed",
                    error = result.message
                )
            }
        }
    }

    fun respondToPrompt(promptId: String, approved: Boolean, text: String = "") {
        val current = _prompt.value ?: return
        if (current.id != promptId) return
        _prompt.value = null
        pendingPrompt?.complete(PromptAnswer(approved, text.trim()))
    }

    override suspend fun confirm(message: String): Boolean =
        awaitPrompt(BrowserPromptKind.CONFIRM, message).approved

    override suspend fun ask(question: String): String {
        val answer = awaitPrompt(BrowserPromptKind.ASK, question)
        return if (answer.approved) answer.text else ""
    }

    override suspend fun requestTakeover(message: String): Boolean =
        awaitPrompt(BrowserPromptKind.TAKEOVER, message).approved

    private suspend fun awaitPrompt(kind: BrowserPromptKind, message: String): PromptAnswer = promptMutex.withLock {
        val deferred = CompletableDeferred<PromptAnswer>()
        pendingPrompt = deferred
        val prompt = BrowserPrompt(kind = kind, message = message)
        _prompt.value = prompt
        try {
            deferred.await()
        } finally {
            if (_prompt.value?.id == prompt.id) _prompt.value = null
            pendingPrompt = null
        }
    }

    private suspend fun onNativeEvent(type: PageAgentRequestType, payload: String) {
        when (type) {
            PageAgentRequestType.ACTIVITY_EVENT -> {
                val summary = activitySummary(payload)
                _state.value = _state.value.copy(status = summary)
                narrate(summary)
            }
            PageAgentRequestType.TASK_RESULT -> {
                _state.value = _state.value.copy(status = "PageAgent returned a task result")
            }
            PageAgentRequestType.AUDIT_EVENT -> persistAudit(payload)
            else -> Unit
        }
    }

    /**
     * Eyes-free (WS4): stash a (origin, task) handed off by the `secure_browser_task` tool, before
     * the Secure Browser screen is composed. The pending origin is loaded when the WebView attaches
     * and the task auto-runs once the PageAgent runtime is ready ([runPendingTaskIfAny]). A blank
     * task means "navigate to the origin only". Safe to call multiple times (last wins); safe to call
     * before [attachWebView] (the pending pair survives until the runtime is ready).
     */
    fun setPendingTask(origin: String, task: String) {
        pendingOrigin = origin
        pendingTask = task
        _state.value = _state.value.copy(currentUrl = origin, status = "Opening $origin…", error = "")
        // If the controller already exists (screen already attached), navigate + run immediately.
        val ctrl = controller
        if (ctrl != null && _state.value.runtimeReady) {
            if (_state.value.currentUrl != origin) ctrl.load(origin)
            runPendingTaskIfAny()
        }
    }

    private fun runPendingTaskIfAny() {
        val task = pendingTask ?: return
        val origin = pendingOrigin
        pendingTask = null
        pendingOrigin = null
        val ctrl = controller ?: return
        if (origin != null && _state.value.currentUrl != origin) ctrl.load(origin)
        if (task.isBlank()) {
            narrate("Opened $origin.")
            return
        }
        narrate("Starting: $task")
        executeTask(task)
    }

    /**
     * Eyes-free (WS4): speak the current page's title + visible body text. Returns the text that
     * was (or would be) spoken via [onPageText] for the screen to display. The read is read-only
     * (it never drives the page); an empty result is spoken as a clear "no readable text" message.
     */
    fun readPageAloud() {
        val ctrl = controller
        if (ctrl == null || !_state.value.runtimeReady) {
            narrate("The secure browser isn't ready yet.")
            return
        }
        ctrl.readPageText { text ->
            if (text.isBlank()) narrate("This page has no readable text yet.")
            else narrate(text)
        }
    }

    /** Speak a narration string through the shared VoiceModule (eyes-free). No-op without a voice module. */
    private fun narrate(text: String) {
        val vm = voiceModule ?: return
        val toSpeak = text.trim()
        if (toSpeak.isBlank() || toSpeak == lastNarration) return
        lastNarration = toSpeak
        cleanupScope.launch {
            vm.speakAwait(toSpeak).onError { msg, _ -> Logger.w("SecureBrowser: narration failed: $msg") }
        }
    }

    /**
     * Eyes-free (WS4): start recording a spoken browser task. The RECORD_AUDIO runtime permission
     * is requested at app startup (this is a direct user-initiated mic tap, like the main Listen
     * button, not a safety-pipeline-gated tool). Stop with [stopVoiceTask] to transcribe + run.
     */
    fun startVoiceTask(context: Context) {
        val vm = voiceModule ?: return
        if (_isListening.value || _state.value.taskRunning) return
        viewModelScope.launch {
            when (val r = vm.startRecording(context, viewModelScope)) {
                is Result.Success -> _isListening.value = true
                is Result.Error -> Logger.w("SecureBrowser: startRecording failed: ${r.message}")
            }
        }
    }

    /** Stop the spoken-task recording, transcribe it offline, and run it as a PageAgent task. */
    fun stopVoiceTask() {
        val vm = voiceModule ?: return
        if (!_isListening.value) return
        _isListening.value = false
        viewModelScope.launch {
            when (val r = vm.stopAndTranscribe()) {
                is Result.Success -> {
                    val transcript = r.data.trim()
                    if (transcript.isNotBlank()) {
                        narrate("Running: $transcript")
                        executeTask(transcript)
                    } else {
                        narrate("I didn't hear a task.")
                    }
                }
                is Result.Error -> narrate("I didn't catch that.")
            }
        }
    }

    private suspend fun persistAudit(payload: String) {
        val event = runCatching {
            json.decodeFromString(BrowserAuditEvent.serializer(), payload)
        }.getOrNull() ?: return

        val status = when (event.decision) {
            "allowed" -> "success"
            "blocked", "user_takeover", "declined_or_blocked" -> "blocked"
            else -> "failed"
        }
        val args = buildJsonObject {
            put("origin", event.origin)
            put("sessionId", event.sessionId)
            put("actionClass", event.actionClass.name)
            put("decision", event.decision)
            put("message", event.message.take(300))
        }
        actionLogDao.insert(
            ActionLogEntity(
                inputText = event.summary.take(500),
                inputType = "secure_browser",
                selectedTool = "browser:${event.actionName}",
                toolArgsJson = json.encodeToString(args),
                riskLevel = browserRiskLevel(event.actionClass),
                status = status,
                errorMessage = event.message.take(300).takeIf { status != "success" },
                createdAt = event.timestampEpochMs
            )
        )
    }

    private fun browserRiskLevel(actionClass: BrowserActionClass): Int = when (actionClass) {
        BrowserActionClass.READ_ONLY -> 0
        BrowserActionClass.ORDINARY_INPUT -> 1
        BrowserActionClass.SENSITIVE_INPUT,
        BrowserActionClass.FILE_TRANSFER,
        BrowserActionClass.LOGIN_HANDOFF,
        BrowserActionClass.FINAL_SUBMISSION -> 2
        BrowserActionClass.LEGAL_ACCEPTANCE,
        BrowserActionClass.PAYMENT,
        BrowserActionClass.CREDENTIAL,
        BrowserActionClass.CAPTCHA -> 3
    }

    private fun activitySummary(payload: String): String = when {
        payload.contains("thinking", ignoreCase = true) -> "Gemma 4 is planning the next page action…"
        payload.contains("executing", ignoreCase = true) -> "PageAgent is executing an authorized DOM action…"
        payload.contains("retry", ignoreCase = true) -> "PageAgent is retrying the model request…"
        payload.contains("error", ignoreCase = true) -> "PageAgent reported an error"
        else -> "PageAgent session active"
    }

    private fun runtimeAssetExists(): Boolean = runCatching {
        appContext.assets.open(SecureWebViewController.RUNTIME_ASSET).use { it.available() > 0 }
    }.getOrDefault(false)

    override fun onCleared() {
        controller?.stop()
        controller = null
        pendingPrompt?.cancel()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        cleanupScope.launch { modelLease.release(restore = true) }
        super.onCleared()
    }

    companion object {
        /**
         * Offline first-run page. Keeping the instructions inside the WebView means the large
         * browser area is useful without internet and "Read Page" can narrate the same workflow.
         * It contains no script, remote resource or form action; the normal PageAgent runtime is
         * injected afterward at the isolated synthetic local-form origin.
         */
        private val WELCOME_PAGE_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>UnoOne Page Agent Home</title>
              <style>
                :root { color-scheme: light dark; font-family: sans-serif; }
                body { margin: 0; padding: 22px; line-height: 1.45; background: #f7f5ff; color: #201a32; }
                h1 { margin: 0 0 6px; font-size: 1.55rem; }
                .tag { display: inline-block; padding: 4px 9px; border-radius: 99px; background: #e4dcff; font-weight: 700; }
                .card { margin-top: 14px; padding: 15px; border-radius: 14px; background: white; border: 1px solid #d8d0ef; }
                h2 { margin: 0 0 8px; font-size: 1.05rem; }
                ol, ul { padding-left: 22px; margin-bottom: 0; }
                .example { padding: 8px 10px; margin-top: 8px; border-radius: 9px; background: #f0ecff; }
                @media (prefers-color-scheme: dark) {
                  body { background: #100d18; color: #f0eaff; }
                  .card { background: #1b1726; border-color: #423858; }
                  .tag, .example { background: #30264a; }
                }
              </style>
            </head>
            <body>
              <span class="tag">Offline Page Agent ready</span>
              <h1>Browse, read and fill pages with UnoOne</h1>
              <p>The planning model runs on your phone. Internet is needed only to open an online page.</p>
              <section class="card">
                <h2>Start in three steps</h2>
                <ol>
                  <li>Enter a website address above and tap Go, or tap Load Form for an offline HTML form.</li>
                  <li>Type a task below, or tap the microphone and speak it.</li>
                  <li>Tap Run. Watch the status card; tap Stop at any time.</li>
                </ol>
              </section>
              <section class="card">
                <h2>Commands you can try</h2>
                <div class="example">Read this page aloud.</div>
                <div class="example">Fill my name as Reetu and my email, then stop before submitting.</div>
                <div class="example">Fill the job application and upload my resume, but do not submit it.</div>
                <div class="example">Select India, accept the newsletter checkbox, and choose tomorrow's date.</div>
              </section>
              <section class="card">
                <h2>Hands-free from the main screen</h2>
                <p>Say: “Open secure browser example.com and fill the contact form, then stop before submit.”</p>
                <p>Say “read this page aloud” after the page opens. When spoken feedback is enabled, UnoOne narrates Page Agent progress and completion.</p>
              </section>
            </body>
            </html>
        """.trimIndent()

        /**
         * Approved HTTPS origins the Secure Browser may automate. Single source of truth lives in
         * [com.unoone.agent.securebrowser.ApprovedOriginPolicy] so the WebView navigation policy and
         * the `secure_browser_task` tool gate agree exactly. Kept as a `val` alias for callers that
         * already read `SecureBrowserViewModel.APPROVED_ORIGINS`.
         */
        val APPROVED_ORIGINS: Set<String> = com.unoone.agent.securebrowser.ApprovedOriginPolicy.APPROVED_ORIGINS
    }
}
