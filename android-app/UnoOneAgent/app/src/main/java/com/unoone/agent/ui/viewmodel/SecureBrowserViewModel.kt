package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.browser.SecureBrowserModelLease
import com.unoone.agent.core.model.Result
import com.unoone.agent.securebrowser.BrowserAuditEvent
import com.unoone.agent.securebrowser.BrowserDomainPolicy
import com.unoone.agent.securebrowser.BrowserEventSink
import com.unoone.agent.securebrowser.BrowserUserInteraction
import com.unoone.agent.securebrowser.PageAgentRequestType
import com.unoone.agent.securebrowser.SecureBrowserNativeHandler
import com.unoone.agent.securebrowser.SecureWebViewController
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.entity.ActionLogEntity
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
    val currentUrl: String = DEFAULT_URL,
    val runtimeReady: Boolean = false,
    val sessionActive: Boolean = false,
    val taskRunning: Boolean = false,
    val modelBackend: String = "",
    val lastResult: String = "",
    val error: String = ""
) {
    companion object {
        const val DEFAULT_URL = "https://unigurus.com"
    }
}

private data class PromptAnswer(val approved: Boolean, val text: String)

/**
 * Owns one UnoOne Secure Browser session and its human-in-the-loop prompts.
 * Raw page content, model prompts and typed form values are never persisted by this ViewModel.
 */
class SecureBrowserViewModel(
    context: Context,
    private val modelLease: SecureBrowserModelLease,
    private val actionLogDao: ActionLogDao
) : ViewModel(), BrowserUserInteraction {

    private val appContext = context.applicationContext
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val promptMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    private val domainPolicy = BrowserDomainPolicy(APPROVED_ORIGINS)
    private var controller: SecureWebViewController? = null
    private var pendingPrompt: CompletableDeferred<PromptAnswer>? = null
    private var attached = false

    private val _state = MutableStateFlow(SecureBrowserUiState())
    val state: StateFlow<SecureBrowserUiState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<BrowserPrompt?>(null)
    val prompt: StateFlow<BrowserPrompt?> = _prompt.asStateFlow()

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
                        eventSink = BrowserEventSink { type, payload -> onNativeEvent(type, payload) }
                    )
                    withContext(Dispatchers.Main.immediate) {
                        controller = SecureWebViewController(
                            context = appContext,
                            webView = webView,
                            domainPolicy = domainPolicy,
                            scope = viewModelScope,
                            requestHandler = handler,
                            onBlockedNavigation = { reason ->
                                _state.value = _state.value.copy(status = "Navigation blocked", error = reason)
                            },
                            onRuntimeReady = {
                                _state.value = _state.value.copy(
                                    phase = "Ready",
                                    status = "PageAgent ready on approved page",
                                    runtimeReady = true,
                                    sessionActive = true,
                                    modelBackend = modelLease.activeBackend(),
                                    error = ""
                                )
                            },
                            onRuntimeError = { message ->
                                _state.value = _state.value.copy(
                                    phase = "Runtime error",
                                    status = "PageAgent failed to initialize",
                                    runtimeReady = false,
                                    error = message
                                )
                            }
                        ).also { it.load(_state.value.currentUrl) }
                    }
                }
            }
        }
    }

    fun navigate(rawUrl: String) {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return
        _state.value = _state.value.copy(currentUrl = clean, status = "Opening approved page…", error = "")
        controller?.load(clean)
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
                _state.value = _state.value.copy(status = activitySummary(payload))
            }
            PageAgentRequestType.TASK_RESULT -> {
                _state.value = _state.value.copy(status = "PageAgent returned a task result")
            }
            PageAgentRequestType.AUDIT_EVENT -> persistAudit(payload)
            else -> Unit
        }
    }

    private suspend fun persistAudit(payload: String) {
        val event = runCatching {
            json.decodeFromString(BrowserAuditEvent.serializer(), payload)
        }.getOrNull() ?: return

        val args = buildJsonObject {
            put("origin", event.origin)
            put("sessionId", event.sessionId)
            put("actionClass", event.actionClass.name)
            put("decision", event.decision)
        }
        actionLogDao.insert(
            ActionLogEntity(
                timestamp = event.timestampEpochMs,
                inputText = event.summary.take(500),
                selectedTool = "browser:${event.actionName}",
                argsJson = json.encodeToString(args),
                riskLevel = event.actionClass.name,
                status = event.decision,
                outputText = event.message.take(300),
                errorMessage = if (event.decision == "blocked") event.message.take(300) else ""
            )
        )
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
        cleanupScope.launch { modelLease.release(restore = true) }
        super.onCleared()
    }

    companion object {
        val APPROVED_ORIGINS: Set<String> = setOf(
            "https://unigurus.com",
            "https://www.unigurus.com",
            "https://uniassist.ai",
            "https://www.uniassist.ai",
            "https://testsprep.in",
            "https://www.testsprep.in",
            "https://inbharat.ai",
            "https://www.inbharat.ai"
        )
    }
}
