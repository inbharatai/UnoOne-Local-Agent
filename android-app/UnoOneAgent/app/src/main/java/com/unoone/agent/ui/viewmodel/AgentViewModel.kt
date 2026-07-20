package com.unoone.agent.ui.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.model.AgentStatus
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.runtime.AgentRuntimeController
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.ui.components.ConfirmationLevel
import com.unoone.agent.voice.VoiceModule
import com.unoone.agent.voice.VoiceRuntimeState
import com.unoone.agent.voice.VoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Coarse device readiness shown as a chip on the Agent screen:
 * - [OFFLINE] (green): Sherpa STT+TTS active, no system fallback, brain loaded.
 * - [LIMITED] (amber): running on the emergency Android STT/TTS fallback.
 * - [NO_MODEL] (red): Sherpa STT/TTS or the LLM brain is unavailable.
 */
enum class OfflineMode { OFFLINE, LIMITED, NO_MODEL }

class AgentViewModel(
    private val orchestrator: AgentOrchestrator,
    voiceModule: VoiceModule,
    private val runtimeController: AgentRuntimeController? = null
) : ViewModel() {

    companion object {
        /** STT results below this trigger one retry prompt ("please repeat"). */
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f

        // ---- C5: hands-free always-listening session -----------------------------------------
        // One tap starts a session: the app keeps listening, transcribes each utterance, runs it
        // through the orchestrator as a VOICE command (so the reply is spoken), then re-arms
        // automatically — no repeated tapping. End by voice ("stop listening") or tapping again.
        private val STOP_PHRASES = setOf(
            "stop listening", "stop listening now", "that's all", "that's all for now",
            "done listening", "stop the session", "exit listening"
        )
        // C6: voice phrases that route to the in-app MediaProjection Read Screen path (C4) instead of
        // the Accessibility-based read_screen tool (which bounces to MIUI settings when Accessibility
        // is off). Intercepted in the hands-free loop before the orchestrator sees them.
        private val READ_SCREEN_PHRASES = setOf(
            "read screen", "read the screen", "read this screen", "read this page",
            "read the page", "what's on screen", "what is on screen", "read aloud"
        )
    }

    val timelineSteps: StateFlow<List<TimelineStep>> = orchestrator.timelineSteps
    val isProcessing: StateFlow<Boolean> = orchestrator.isProcessing
    val isBlindAidActive: StateFlow<Boolean> = orchestrator.isBlindAidActive
    private val fallbackEnabled = MutableStateFlow(AgentRuntimeGate.isEnabled())
    val isAgentEnabled: StateFlow<Boolean> =
        runtimeController?.isAgentEnabled ?: fallbackEnabled.asStateFlow()

    // Single shared VoiceModule instance — also used by the orchestrator for speak()
    val voiceModuleInstance: VoiceModule = voiceModule

    // Thread-safe listening state, observable by Compose
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<Pair<String, ConfirmationLevel>?>(null)
    val pendingConfirmation: StateFlow<Pair<String, ConfirmationLevel>?> = _pendingConfirmation.asStateFlow()

    // Thread-safe confirmation callback using AtomicReference
    private val confirmationCallback = AtomicReference<((Boolean) -> Unit)?>(null)

    // Offline-mode chip state. Polled by the screen (VoiceModule exposes @Volatile state, not a
    // Flow, so we refresh on a cadence and on lifecycle resume).
    private val _offlineMode = MutableStateFlow(OfflineMode.NO_MODEL)
    val offlineMode: StateFlow<OfflineMode> = _offlineMode.asStateFlow()

    // One-shot low-confidence retry: true while we're re-listening for a repeated utterance so
    // a second low-confidence result is not retried again (avoids infinite re-prompt loops).
    private var retryArmed = false

    // C5: the always-listening hands-free session. The big LISTEN button starts/stops it. While
    // active, the loop captures an utterance (record → VAD end-of-speech → transcribe), feeds it to
    // the orchestrator as VOICE (so the reply is spoken), waits for the command to finish, then
    // re-arms — no repeated tapping. isListening still drives the waveform; isHandsFree tells the
    // UI the button is now "Stop listening".
    private val _isHandsFree = MutableStateFlow(false)
    val isHandsFree: StateFlow<Boolean> = _isHandsFree.asStateFlow()
    private var sessionJob: Job? = null
    private var listeningJob: Job? = null
    private var commandJob: Job? = null
    private var ocrJob: Job? = null
    private var documentJob: Job? = null
    private var runtimeStateJob: Job? = null

    /** Recompute the offline-mode chip from current engine + brain state. Call from the screen. */
    fun refreshOfflineMode() {
        val stt = voiceModuleInstance.sttState
        val tts = voiceModuleInstance.ttsState
        val llm = orchestrator.isLlmLoaded()
        _offlineMode.value = when {
            // Sherpa missing for STT or TTS, AND no brain → nothing works offline.
            stt == VoiceRuntimeState.UNAVAILABLE && tts == VoiceRuntimeState.UNAVAILABLE && !llm -> OfflineMode.NO_MODEL
            // Emergency system STT/TTS fallback in use anywhere → online-ish / limited.
            stt == VoiceRuntimeState.SYSTEM_FALLBACK || tts == VoiceRuntimeState.SYSTEM_FALLBACK -> OfflineMode.LIMITED
            // Sherpa STT/TTS active and no fallback, with the brain loaded → fully offline.
            stt == VoiceRuntimeState.SHERPA && tts == VoiceRuntimeState.SHERPA && llm -> OfflineMode.OFFLINE
            // Sherpa voice available but brain not yet loaded → still offline voice, but flag limited.
            stt == VoiceRuntimeState.SHERPA || tts == VoiceRuntimeState.SHERPA -> OfflineMode.LIMITED
            else -> OfflineMode.NO_MODEL
        }
    }

    fun startListening(context: Context) {
        if (!AgentRuntimeGate.isEnabled()) return
        if (_isListening.value || isProcessing.value) return
        listeningJob?.cancel()
        VoiceService.foregroundSessionActive = true
        listeningJob = viewModelScope.launch {
            val result = voiceModuleInstance.startRecording(context, viewModelScope)
            if (result is Result.Success) {
                _isListening.value = true
            } else if (result is Result.Error) {
                VoiceService.foregroundSessionActive = _isHandsFree.value
                Logger.w("Failed to start recording: ${result.message}")
            }
        }
    }

    fun stopListening() {
        if (!_isListening.value) return
        _isListening.value = false
        _amplitude.value = 0f
        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            var shouldRetry = false
            try {
                val result = voiceModuleInstance.stopAndTranscribe()
                if (result is Result.Success && result.data.isNotBlank()) {
                    val confidence = voiceModuleInstance.lastSttConfidence
                    // Low-confidence retry: ask the user to repeat once, then re-listen. Don't loop.
                    if (confidence < LOW_CONFIDENCE_THRESHOLD && !retryArmed) {
                        retryArmed = true
                        voiceModuleInstance.speakAwait("Sorry, I didn't catch that clearly. Could you please repeat?")
                        Logger.i("AgentViewModel: Low STT confidence (${"%.2f".format(confidence)}); re-listening once.")
                        shouldRetry = true
                    } else {
                        retryArmed = false
                        orchestrator.processCommand(result.data, InputType.VOICE)
                    }
                } else if (result is Result.Error) {
                    retryArmed = false
                    Logger.e("Speech transcription failed: ${result.message}")
                } else {
                    retryArmed = false
                }
            } finally {
                VoiceService.foregroundSessionActive = _isHandsFree.value
            }
            if (shouldRetry && AgentRuntimeGate.isEnabled()) {
                startListening(com.unoone.agent.UnoOneApplication.appContext)
            }
        }
    }

    /**
     * C3: Cancel the in-flight command (or the pending system-permission command) and un-brick the
     * UI. Always callable — the blind user is never trapped in a stuck "Reading screen" / processing
     * state. Delegates to the orchestrator which cancels the run, clears the pending command, and
     * speaks "Stopped."
     */
    fun cancelCommand() {
        orchestrator.cancelCurrentCommand()
    }

    // ---- C4: Read Screen via MediaProjection (in-app consent, no MIUI settings bounce) ----------
    // ML Kit text recognizer is lazy inside OcrControl, so construction is cheap and safe at first use.
    private val ocrControl by lazy {
        com.unoone.agent.phonecontrol.OcrControl(com.unoone.agent.UnoOneApplication.appContext)
    }

    /** Last Read Screen OCR text (null until a read completes); surfaced in the UI for sighted users. */
    private val _lastReadScreenText = MutableStateFlow<String?>(null)
    val lastReadScreenText: StateFlow<String?> = _lastReadScreenText.asStateFlow()

    /**
     * C4: Read the screen via MediaProjection (in-app one-tap consent) + on-device OCR, then SPEAK
     * the result. This is the eyes-free Read Screen path that never bounces to MIUI Accessibility
     * settings and never leaves `isProcessing` stuck — a clean request→result Activity flow with a
     * spoken result. Falls back to a spoken instruction if consent is denied. The Accessibility-based
     * `read_screen` tool stays available for cross-app reading when Accessibility is enabled.
     */
    fun readScreenViaMediaProjection(context: Context) {
        if (!AgentRuntimeGate.isEnabled()) return
        // One-shot consent listener. ScreenshotPermissionActivity writes the granted MediaProjection
        // into ScreenshotCapture.mediaProjection and fires this callback on grant/deny.
        com.unoone.agent.phonecontrol.ScreenshotCapture.permissionListener = { granted ->
            if (granted) {
                runOcrAndSpeak()
            } else {
                viewModelScope.launch {
                    runCatching {
                        voiceModuleInstance.speakAwait("Screen reading needs your permission. Tap read screen again to allow it, or say stop.")
                    }
                }
            }
        }
        if (com.unoone.agent.phonecontrol.ScreenshotCapture.hasPermission()) {
            runOcrAndSpeak()
        } else {
            com.unoone.agent.screenshot.ScreenshotPermissionActivity.launch(context)
        }
    }

    private fun runOcrAndSpeak() {
        ocrJob?.cancel()
        ocrJob = viewModelScope.launch(Dispatchers.IO) {
            // recognizeScreen() calls the blocking ScreenshotCapture.captureScreen() (~500ms poll),
            // so it must run off the main thread.
            val result = ocrControl.recognizeScreen()
            val spoken = when (result) {
                is Result.Success -> result.data.trim().ifBlank { "I couldn't see any text on the screen." }
                is Result.Error -> "I couldn't read the screen. ${result.message}"
            }
            _lastReadScreenText.value = (result as? Result.Success)?.data
            runCatching { voiceModuleInstance.speakAwait(spoken) }
        }
    }

    // ---- C8: load a PDF / image / Excel / HTML / text file for the brain to read + work on --------
    private val documentLoader by lazy {
        com.unoone.agent.phonecontrol.document.DocumentLoader(com.unoone.agent.UnoOneApplication.appContext)
    }

    private val documentFillEngine by lazy {
        com.unoone.agent.phonecontrol.document.DocumentFillEngine(com.unoone.agent.UnoOneApplication.appContext)
    }
    private var documentFillSourceUri: Uri? = null

    private val _editableDocument = MutableStateFlow<com.unoone.agent.phonecontrol.document.EditableDocumentTemplate?>(null)
    val editableDocument: StateFlow<com.unoone.agent.phonecontrol.document.EditableDocumentTemplate?> =
        _editableDocument.asStateFlow()

    private val _isFillingDocument = MutableStateFlow(false)
    val isFillingDocument: StateFlow<Boolean> = _isFillingDocument.asStateFlow()

    private val _documentFillMessage = MutableStateFlow("")
    val documentFillMessage: StateFlow<String> = _documentFillMessage.asStateFlow()

    private val _documentFillPickerRequest = MutableStateFlow<String?>(null)
    val documentFillPickerRequest: StateFlow<String?> = _documentFillPickerRequest.asStateFlow()

    fun consumeDocumentFillPickerRequest() {
        _documentFillPickerRequest.value = null
    }

    fun inspectDocumentForFilling(uri: Uri, mimeType: String?) {
        if (!AgentRuntimeGate.isEnabled()) return
        if (_isFillingDocument.value) return
        _isFillingDocument.value = true
        _documentFillMessage.value = "Inspecting document fields offline…"
        documentJob?.cancel()
        documentJob = viewModelScope.launch(Dispatchers.IO) {
            when (val result = documentFillEngine.inspect(uri, mimeType)) {
                is Result.Success -> {
                    documentFillSourceUri = uri
                    _editableDocument.value = result.data
                    _documentFillMessage.value = "Found ${result.data.fields.size} editable field(s)."
                    val labels = result.data.fields.take(5).joinToString { it.label }
                    runCatching {
                        voiceModuleInstance.speakAwait(
                            "Loaded ${result.data.displayName}. Found ${result.data.fields.size} editable fields: $labels. Fill them, then save a new copy."
                        )
                    }
                }
                is Result.Error -> {
                    documentFillSourceUri = null
                    _editableDocument.value = null
                    _documentFillMessage.value = result.message
                    runCatching { voiceModuleInstance.speakAwait("I couldn't prepare that document. ${result.message}") }
                }
            }
            _isFillingDocument.value = false
        }
    }

    fun fillDocumentCopy(outputUri: Uri, values: Map<String, String>) {
        if (!AgentRuntimeGate.isEnabled()) return
        val source = documentFillSourceUri ?: return
        val template = _editableDocument.value ?: return
        if (_isFillingDocument.value) return
        _isFillingDocument.value = true
        _documentFillMessage.value = "Writing and verifying a new copy…"
        documentJob?.cancel()
        documentJob = viewModelScope.launch(Dispatchers.IO) {
            when (val result = documentFillEngine.fillCopy(source, outputUri, template, values)) {
                is Result.Success -> {
                    _documentFillMessage.value =
                        "Saved ${result.data.displayName}; verified ${result.data.fieldsWritten} field(s). Original unchanged."
                    runCatching {
                        voiceModuleInstance.speakAwait(
                            "Done. Saved ${result.data.displayName} with ${result.data.fieldsWritten} verified fields. The original was not changed."
                        )
                    }
                }
                is Result.Error -> {
                    _documentFillMessage.value = result.message
                    runCatching { voiceModuleInstance.speakAwait("The document was not saved. ${result.message}") }
                }
            }
            _isFillingDocument.value = false
        }
    }

    fun clearEditableDocument() {
        documentFillSourceUri = null
        _editableDocument.value = null
        _documentFillMessage.value = ""
    }

    /** The currently loaded document (name + extracted text), or null. Surfaced in the UI. */
    private val _loadedDocument = MutableStateFlow<com.unoone.agent.core.document.ExtractedDoc?>(null)
    val loadedDocument: StateFlow<com.unoone.agent.core.document.ExtractedDoc?> = _loadedDocument.asStateFlow()

    /** Loading-in-progress flag so the UI can show a spinner during OCR/Parsing. */
    private val _isLoadingDocument = MutableStateFlow(false)
    val isLoadingDocument: StateFlow<Boolean> = _isLoadingDocument.asStateFlow()

    /*
     * IMPORTANT: this is deliberately the only init block, and it stays below every state holder,
     * job reference, lazy controller and transient field used by clearTransientStateForDisable().
     * StateFlow collectors launched on Dispatchers.Main.immediate may receive the persisted value
     * before the constructor returns, so moving only the crashing field would leave another
     * initialization-order trap behind.
     */
    init {
        orchestrator.setVoiceModule(voiceModuleInstance)

        voiceModuleInstance.onAmplitude = { amp ->
            _amplitude.value = amp
        }

        orchestrator.onConfirmationRequired = { message, callback ->
            val level = if (message.startsWith("SECURITY CHECK")) ConfirmationLevel.STRONG_CONFIRM
            else ConfirmationLevel.CONFIRM
            _pendingConfirmation.value = message to level
            confirmationCallback.set(callback)
        }

        orchestrator.onDocumentFillRequest = { format ->
            _documentFillPickerRequest.value = format.lowercase()
        }

        runtimeController?.let { controller ->
            runtimeStateJob = viewModelScope.launch {
                controller.isAgentEnabled.collect { enabled ->
                    if (!enabled) clearTransientStateForDisable()
                }
            }
        }
    }

    /**
     * C8: pick a document via SAF, extract its text (PDF→page-OCR, image→OCR, xlsx→SAX, HTML/text→
     * UTF-8), and stash it. Speaks a confirmation with the char count so a blind user knows it loaded.
     */
    fun loadDocument(context: Context, uri: Uri, mimeType: String?) {
        if (!AgentRuntimeGate.isEnabled()) return
        if (_isLoadingDocument.value) return
        _isLoadingDocument.value = true
        documentJob?.cancel()
        documentJob = viewModelScope.launch(Dispatchers.IO) {
            val result = documentLoader.load(uri, mimeType)
            val doc = (result as? Result.Success)?.data
            _loadedDocument.value = doc
            _isLoadingDocument.value = false
            if (doc != null) {
                val kindWord = when (doc.kind) {
                    com.unoone.agent.core.document.DocKind.PDF -> "PDF"
                    com.unoone.agent.core.document.DocKind.IMAGE -> "image"
                    com.unoone.agent.core.document.DocKind.XLSX -> "spreadsheet"
                    com.unoone.agent.core.document.DocKind.DOCX -> "Word document"
                    com.unoone.agent.core.document.DocKind.HTML -> "web page"
                    com.unoone.agent.core.document.DocKind.CSV -> "C S V"
                    com.unoone.agent.core.document.DocKind.TEXT -> "text file"
                    else -> "document"
                }
                val trunc = if (doc.truncated) " I could only read the first part, it is large." else ""
                runCatching {
                    voiceModuleInstance.speakAwait("Loaded $kindWord: ${doc.name}. ${doc.text.length} characters.$trunc Ask me about it, or say summarize this document.")
                }
            } else {
                runCatching {
                    voiceModuleInstance.speakAwait("I couldn't read that document. ${(result as? Result.Error)?.message ?: ""}")
                }
            }
        }
    }

    /** Clears the loaded document. */
    fun clearDocument() {
        _loadedDocument.value = null
    }

    /**
     * C8: ask the brain about the loaded document. Builds a single prompt that embeds the extracted
     * text as context and the user's question/instruction, then runs it through the orchestrator as a
     * VOICE command so the answer is SPOKEN (eyes-free) and shown in the timeline. A blank question
     * defaults to "Summarize this document."
     */
    fun askAboutDocument(question: String) {
        if (!AgentRuntimeGate.isEnabled()) return
        val doc = _loadedDocument.value ?: return
        val q = question.trim().ifBlank { "Summarize this document." }
        val prompt = buildString {
            append(q)
            append("\n\nHere is the content of the document \"")
            append(doc.name)
            append("\" (")
            append(doc.kind.name.lowercase())
            if (doc.truncated) append(", first part only")
            append("):\n\"\"\"\n")
            append(doc.text)
            append("\n\"\"\"\n\nBased only on the document above, ")
            append(q.trimEnd('.', '?', '!'))
            append(".")
        }
        commandJob = viewModelScope.launch {
            orchestrator.processCommand(prompt, InputType.VOICE)
        }
    }

    /**
     * C5: Start/stop the always-listening hands-free session. One tap starts it: the app keeps
     * listening, speaks each reply, and re-arms automatically — a blind user gives commands by voice
     * without tapping again. Tapping again (or voice "stop listening") ends it. Requires RECORD_AUDIO
     * (the UI grants it before calling). The mic is owned solely by this session while active —
     * [VoiceService.foregroundSessionActive] tells the background KWS loop to yield its recorder.
     */
    fun toggleHandsFreeSession(context: Context) {
        if (_isHandsFree.value) {
            stopHandsFreeSession()
        } else {
            startHandsFreeSession(context)
        }
    }

    fun startHandsFreeSession(context: Context) {
        if (!AgentRuntimeGate.isEnabled()) return
        if (_isHandsFree.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w("AgentViewModel: cannot start hands-free session — RECORD_AUDIO not granted")
            return
        }
        // C5: single mic owner — tell the background KWS loop to release its recorder so the two
        // AudioRecord instances don't fight for the mic (the prior "listen is slow/erratic" cause).
        VoiceService.foregroundSessionActive = true
        _isHandsFree.value = true
        _isListening.value = true
        sessionJob = viewModelScope.launch {
            try {
                // Finish the cue before opening the mic. Running these concurrently let UnoOne
                // transcribe its own "I'm listening" prompt as the user's command.
                runCatching { voiceModuleInstance.speakAwait("I'm listening. Say a command.") }
                while (isActive && _isHandsFree.value) {
                    val utterance = captureUtterance(context)
                    if (!_isHandsFree.value) break
                    if (utterance.isNullOrBlank()) {
                        // No speech captured this round — brief pause then re-arm (no spam).
                        delay(300)
                        continue
                    }
                    if (utterance.lowercase().trim() in STOP_PHRASES) {
                        stopHandsFreeSession()
                        break
                    }
                    // C6: route "read screen" to the in-app MediaProjection path (no settings bounce).
                    if (utterance.lowercase().trim() in READ_SCREEN_PHRASES) {
                        runCatching { voiceModuleInstance.stopSpeaking() }
                        readScreenViaMediaProjection(context)
                        try {
                            // The MediaProjection read is a short spoken flow; brief settle before re-arming.
                            delay(1200)
                        } catch (_: kotlinx.coroutines.CancellationException) { break }
                        continue
                    }
                    // Stop any TTS still playing so we don't talk over the user, then run the command.
                    runCatching { voiceModuleInstance.stopSpeaking() }
                    orchestrator.processCommand(utterance, InputType.VOICE)
                    // Wait for the command (incl. its spoken reply) to finish before re-listening,
                    // so the mic never captures the agent's own voice.
                    try {
                        orchestrator.isProcessing.first { !it }
                    } catch (_: kotlinx.coroutines.CancellationException) { break }
                    delay(500)
                }
            } finally {
                _isListening.value = false
                _amplitude.value = 0f
                VoiceService.foregroundSessionActive = false
            }
        }
    }

    fun stopHandsFreeSession(announce: Boolean = true) {
        val wasActive = _isHandsFree.value
        _isHandsFree.value = false
        sessionJob?.cancel()
        sessionJob = null
        runCatching { voiceModuleInstance.stopRecording() }
        _isListening.value = false
        _amplitude.value = 0f
        // Release the mic back to the background KWS loop.
        VoiceService.foregroundSessionActive = false
        if (wasActive && announce && AgentRuntimeGate.isEnabled()) viewModelScope.launch {
            // Offline synthesis is CPU-heavy; keep it off the UI thread and wait for playback so a
            // rapid start/stop cannot trigger a watchdog stall or overlap the next recording.
            runCatching { voiceModuleInstance.speakAwait("Stopped listening.") }
        }
    }

    /**
     * C5: record one utterance with VAD-based end-of-speech detection, then transcribe it. Returns
     * the transcript (trimmed) or null/blank when nothing was captured. Uses [amplitude] (fed by the
     * recorder's amplitude callback) to detect speech start, then ~1.5s of trailing silence to end.
     */
    private suspend fun captureUtterance(context: Context): String? {
        val start = voiceModuleInstance.startRecording(context, viewModelScope)
        if (start is Result.Error) {
            Logger.w("AgentViewModel: hands-free record start failed: ${start.message}")
            return null
        }
        _isListening.value = true
        var speechStarted = false
        var silenceSince = 0L
        val loopStart = System.currentTimeMillis()
        try {
            // Cancellation surfaces at delay() below (CancellationException); _isHandsFree gates exit.
            while (
                _isHandsFree.value &&
                (System.currentTimeMillis() - loopStart) < com.unoone.agent.voice.VoiceActivityPolicy.MAX_UTTERANCE_MS
            ) {
                delay(150)
                val amp = _amplitude.value
                if (com.unoone.agent.voice.VoiceActivityPolicy.isSpeech(amp)) {
                    speechStarted = true
                    silenceSince = 0L
                } else if (speechStarted) {
                    if (silenceSince == 0L) silenceSince = System.currentTimeMillis()
                    if (
                        System.currentTimeMillis() - silenceSince >=
                        com.unoone.agent.voice.VoiceActivityPolicy.TRAILING_SILENCE_MS
                    ) break
                }
            }
        } finally {
            _isListening.value = false
        }
        val res = voiceModuleInstance.stopAndTranscribe()
        return (res as? Result.Success)?.data?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setBlindAidActive(active: Boolean) {
        if (active && !AgentRuntimeGate.isEnabled()) return
        // Direct toggle when the user explicitly presses the UI button — no safety
        // confirmation needed because the user initiated this action deliberately.
        // Voice/text commands like "activate blind aid" still go through the full
        // safety pipeline via processCommand.
        orchestrator.setBlindAidActive(active)
    }

    fun onTextCommand(text: String) {
        if (!AgentRuntimeGate.isEnabled()) return
        commandJob = viewModelScope.launch {
            orchestrator.processCommand(text, InputType.TEXT)
        }
    }

    /**
     * Eyes-free (WS5): inject a command as a VOICE input so the orchestrator speaks the result
     * (milestone narration + the final answer). Used by the main-page "Read Screen" capability so a
     * blind user hears what's on screen instead of only seeing it in the timeline. The full safety
     * pipeline (permissions, risk, confirmation) still applies — this only sets the input type.
     */
    fun onVoiceCommand(text: String) {
        if (!AgentRuntimeGate.isEnabled()) return
        commandJob = viewModelScope.launch {
            orchestrator.processCommand(text, InputType.VOICE)
        }
    }

    /**
     * Eyes-free (WS4): wire the `secure_browser_task` tool to the UI. The handler navigates to the
     * Secure Browser screen and stashes the (origin, task) so the PageAgent run starts once the
     * Gemma lease is acquired and the runtime is ready. Set once from [com.unoone.agent.UnoOneApp]
     * which owns the nav controller + SecureBrowserViewModel. Pass null to detach (e.g. on dispose).
     */
    fun setSecureBrowserTaskHandler(handler: ((origin: String, task: String) -> Unit)?) {
        orchestrator.onSecureBrowserTask = handler
    }

    fun onQuickAction(label: String) {
        if (!AgentRuntimeGate.isEnabled()) return
        commandJob = viewModelScope.launch {
            val command = when (label) {
                "Create Note" -> "Create a note"
                "Open Chrome" -> "Open Chrome"
                "Calendar" -> "Open calendar"
                "Open App" -> "Open WhatsApp"
                else -> label
            }
            orchestrator.processCommand(command, InputType.TEXT)
        }
    }

    fun respondToConfirmation(allowed: Boolean) {
        confirmationCallback.getAndSet(null)?.invoke(allowed)
        _pendingConfirmation.value = null
    }

    fun disableAgent() {
        clearTransientStateForDisable()
        runtimeController?.disableAgent()
        fallbackEnabled.value = false
    }

    private fun clearTransientStateForDisable() {
        sessionJob?.cancel()
        sessionJob = null
        listeningJob?.cancel()
        listeningJob = null
        commandJob?.cancel()
        commandJob = null
        ocrJob?.cancel()
        ocrJob = null
        documentJob?.cancel()
        documentJob = null

        stopHandsFreeSession(announce = false)
        runCatching { voiceModuleInstance.stopSpeaking() }
        orchestrator.shutdownForDisable()

        _isListening.value = false
        _isHandsFree.value = false
        _amplitude.value = 0f
        retryArmed = false
        confirmationCallback.set(null)
        _pendingConfirmation.value = null
        _isLoadingDocument.value = false
        _isFillingDocument.value = false
        _loadedDocument.value = null
        documentFillSourceUri = null
        _editableDocument.value = null
        _documentFillPickerRequest.value = null
        _documentFillMessage.value = ""
        _lastReadScreenText.value = null
        com.unoone.agent.phonecontrol.ScreenshotCapture.permissionListener = null
    }

    fun enableAgent() {
        runtimeController?.enableAgent()
        fallbackEnabled.value = true
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT release voiceModuleInstance here — it is the application-scoped shared
        // instance owned by UnoOneApplication. Releasing it would destroy the VoiceModule
        // used by the orchestrator, broadcast receiver, and FloatingAgentService.
        // C4: release the OCR recognizer if it was ever spun up.
        runCatching { ocrControl.release() }
        // C8: release the document loader's OCR recognizer.
        runCatching { documentLoader.release() }
        runtimeStateJob?.cancel()
        runtimeStateJob = null
        orchestrator.onDocumentFillRequest = null
    }
}
