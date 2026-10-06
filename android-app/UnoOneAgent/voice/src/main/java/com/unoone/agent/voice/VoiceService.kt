package com.unoone.agent.voice

import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.runtime.VoiceAdmissionTicket
import com.unoone.agent.core.voice.VoiceIngress
import com.unoone.agent.core.latency.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.KeywordSpotterEngine
import com.unoone.agent.voice.stt.NativeLifecycleGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

class VoiceService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var engineInitJob: Job? = null
    private var monitoringJob: Job? = null

    @Volatile
    private var enginesInitialized = false

    @Volatile
    private var monitoringStarted = false

    /** Serializes runtime STT/TTS rebuilds so rapid language switches never overlap on the IO pool. */
    private val reinitLock = Mutex()

    private val recorder = AudioRecorder(requestEchoCancellation = true)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val kwsLifecycle = NativeLifecycleGate<KeywordSpotterEngine> { it.release() }
    private val wakeActivationGate = WakeActivationGate()
    private val speechDetector = AdaptiveSpeechDetector()

    var onWakeWordDetected: (() -> Unit)? = null

    @Volatile
    private var isListeningForCommand = false

    companion object {
        private val passiveHandoff = PassiveCaptureHandoff()
        private val liveService = java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<VoiceService>?>(null)
        fun claimPassivePause(token: Any): Boolean = passiveHandoff.claim(token)
        fun isForegroundCaptureClaimed(): Boolean = passiveHandoff.paused()
        suspend fun yieldPassiveCapture(token: Any): Boolean = passiveHandoff.acknowledge(token) {
            val service = liveService.get()?.get()
            if (service == null) processMicrophoneLease.isIdle()
            else kotlinx.coroutines.withTimeoutOrNull(2_000L) { service.haltPassiveCapture() } ?: false
        }
        fun releasePassivePause(token: Any) {
            if (passiveHandoff.release(token)) liveService.get()?.get()?.resumePassiveCapture()
        }
        private const val CHANNEL_ID = "voice_service_channel"
        private const val NOTIFICATION_ID = 1001
        /** One second of 16 kHz mono PCM16; safely exceeds the bundled KWS 45-frame minimum. */
        private const val KWS_SAFE_PCM_BYTES = 16_000 * 2
        const val ACTION_VOICE_COMMAND = "com.unoone.agent.VOICE_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_COMMAND_GENERATION = "command_generation"
        /**
         * Delivered via startService to an already-running service so it rebuilds STT/TTS for the
         * newly-selected voice language without restarting the wake-word loop. Sent by Settings.
         */
        const val ACTION_REINIT_LANG = "com.unoone.agent.REINIT_VOICE_LANG"

        /**
         * Static callback for voice commands. Set by the Application layer
         * to route transcribed commands without cross-module coupling.
         * Replaces the direct UnoOneApplication reference for modularity.
         */
        var voiceCommandCallback: ((String) -> Unit)? = null
        var voiceTicketCallback: ((VoiceAdmissionTicket) -> Unit)? = null
        /** Application supplies scope/review facts; voice never imports the application module. */
        @Volatile var captureMetadataProvider: (() -> VoiceIngress)? = null
        private fun captureTicket(): VoiceAdmissionTicket {
            val ingress = captureMetadataProvider?.invoke() ?: VoiceIngress(
                java.util.UUID.randomUUID().toString(), "", GlobalTaskCancellation.generation,
                SystemClock.elapsedRealtime())
            // Capture authority is frozen even for idle PCM; diagnostics are allocated only at STT admission.
            return WakeCaptureTrace.captured(ingress)
        }
        private fun admitTranscription(capture: VoiceAdmissionTicket): VoiceAdmissionTicket =
            WakeCaptureTrace.admit(capture, GlobalTaskCancellation.generation, VoiceLatency.recorder)

        private fun dispatchVoice(text: String, capture: VoiceAdmissionTicket) {
            if (!capture.isCurrent()) {
                VoiceLatency.recorder.closeCapture(capture.latencyToken, LatencyOutcome.CANCELLED)
                return
            }
            val ticket = capture.copy(text = text)
            VoiceLatency.recorder.mark(ticket.latencyToken, LatencyStage.ADMISSION)
            val callback = voiceTicketCallback
            if (callback != null) {
                callback(ticket)
                // Callback queues Unified.accept asynchronously: transfer before producer finally.
                VoiceLatency.recorder.acceptOwnership(ticket.latencyToken)
            }
            // Bare text carries no authority. In particular it must never approve a later review.
            else if (VoiceControlPolicy.isStop(text)) voiceCommandCallback?.invoke(text)
        }

        /** Preserve capture facts when an in-process producer chooses Intent transport. */
        fun putAdmissionTicket(intent: Intent, ticket: VoiceAdmissionTicket): Intent = intent.apply {
            putExtra(EXTRA_COMMAND, ticket.text)
            putExtra(EXTRA_COMMAND_GENERATION, ticket.generation)
            putExtra("voice_request_id", ticket.requestId)
            putExtra("voice_capture_start", ticket.captureStartMono)
            putExtra("voice_review_id", ticket.liveReviewId)
            ticket.underlyingAppEvidence?.let {
                putExtra("voice_app_package", it.packageName)
                putExtra("voice_app_window", it.windowId)
                putExtra("voice_app_observed", it.observedAtMono)
                putExtra("voice_app_is_application", it.isApplicationWindow)
                putExtra("voice_app_is_overlay", it.isOwnOverlay)
            }
            VoiceLatency.recorder.bindRequest(ticket.requestId, ticket.latencyToken)
        }

        /**
         * Eyes-free (WS2): static wake callback. Set by the Application layer to speak the
         * "Yes, I'm listening" cue (via the shared VoiceModule) when the KWS loop fires, without
         * cross-module coupling — mirrors [voiceCommandCallback]. Invoked from the spotting loop
         * off the audio thread so the cue does not block command capture.
         */
        var onWakeWord: (suspend () -> Unit)? = null

        /**
         * Application-owned speech runtime. The service owns continuous capture/KWS only; final
         * PCM decoding and TTS use this shared VoiceModule.
         */
        @Volatile
        var sharedVoiceModuleProvider: (() -> VoiceModule?)? = null

        /**
         * C5: when true, the in-app hands-free session owns the mic — the background KWS loop must
         * release its recorder and skip spotting so the two AudioRecord instances don't contend
         * (the prior "listen is slow/erratic" cause). Set by AgentViewModel when a session starts.
         */
        var foregroundSessionActive: Boolean = false

        /**
         * A voice command may be waiting for a safety decision. Keep the wake loop available for
         * the exact follow-up (for example, "Uno confirm") even though the original command still
         * owns the foreground-task slot. Agent speech remains an independent hard stop.
         */
        @Volatile
        var awaitingVoiceConfirmation: Boolean = false

        private val agentSpeechOwners = SpeechReferences()
        private val foregroundTaskOwners = AtomicInteger(0)

        /** Prevents the wake recorder from transcribing UnoOne's own TTS. */
        fun beginAgentSpeech() {
            agentSpeechOwners.beginLegacy()
        }

        fun endAgentSpeech() {
            agentSpeechOwners.endLegacy()
        }

        fun acquireAgentSpeech(): SpeechReferences.Owner = agentSpeechOwners.acquire()

        fun endAgentSpeech(owner: SpeechReferences.Owner) { agentSpeechOwners.release(owner) }

        fun isAgentSpeaking(): Boolean = agentSpeechOwners.isBusy()

        /**
         * Marks ordinary-command dispatch busy. Native/UI/model work does not own the mic;
         * wake-qualified Stop monitoring remains available unless actual capture or playback
         * policy blocks it. Physical capture uses claimPassivePause/yield/release instead.
         */
        fun beginForegroundTask() {
            foregroundTaskOwners.incrementAndGet()
        }

        fun endForegroundTask() {
            foregroundTaskOwners.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            liveService.get()?.get()?.resumePassiveCapture()
        }

        fun isForegroundTaskActive(): Boolean = foregroundTaskOwners.get() > 0

        fun clearAudioOwnership() {
            foregroundSessionActive = false
            awaitingVoiceConfirmation = false
            agentSpeechOwners.clear()
            foregroundTaskOwners.set(0)
        }

        fun start(context: Context) {
            if (!AgentRuntimeGate.isEnabled()) return
            val intent = Intent(context, VoiceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            context.stopService(intent)
        }

        /** Ask a running VoiceService to rebuild STT/TTS for the current voice language pref. */
        fun reinitLanguage(context: Context) {
            if (!AgentRuntimeGate.isEnabled()) return
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_REINIT_LANG)
            runCatching { context.startService(intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        liveService.set(java.lang.ref.WeakReference(this))
        createNotificationChannel()
        if (!AgentRuntimeGate.isEnabled()) {
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, createNotification("Listening locally — Mic active. Say 'UnoOne' or 'Listen' to give a command."))
        VoiceAgentRuntime.transition(VoiceAgentState.INITIALISING, "voice service created")
        Logger.i("VoiceService: Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRuntimeGate.isEnabled()) {
            if (recorder.isRecording()) recorder.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REINIT_LANG) {
            // Settings already rebuilt the one shared VoiceModule; the service intentionally owns
            // no duplicate STT/TTS runtime.
            Logger.i("VoiceService: shared voice language changed")
            return START_STICKY
        }
        if (intent?.action == ACTION_VOICE_COMMAND) {
            // Eyes-free (WS2): a pre-transcribed command (e.g. from the main-page Listen button or the
            // floating bubble) is injected through the one orchestrator path as a VOICE command —
            // the same route a wake-word + STT transcript takes, so confirmation/narration/safety all
            // apply identically. Declared since launch but previously unhandled.
            val command = intent.getStringExtra(EXTRA_COMMAND)
            // Delivery can be delayed by Android; never attach fresh authority to queued text.
            val capturedGeneration = intent.getLongExtra(EXTRA_COMMAND_GENERATION, Long.MIN_VALUE)
            val injected = injectedTicket(intent, command.orEmpty(), capturedGeneration)
            try {
                if (!command.isNullOrBlank() && (VoiceControlPolicy.isStop(command) || ordinarySpeechAllowed(capturedGeneration))) {
                    Logger.i("VoiceService: received injected voice command")
                    dispatchVoice(command, injected)
                }
            } finally {
                VoiceLatency.recorder.closeCapture(injected.latencyToken,
                    if (injected.isCurrent()) LatencyOutcome.REJECTED else LatencyOutcome.CANCELLED)
            }
            return START_STICKY
        }
        ensureEnginesAndMonitoring()
        return START_STICKY
    }

    /**
     * Initializes native speech models once on the service IO scope. Android invokes
     * [onStartCommand] on the main thread, and doing this work inline previously stalled the whole
     * process for several seconds during a cold offline launch.
     */
    private fun ensureEnginesAndMonitoring() {
        if (!kwsLifecycle.isOpen()) return
        if (enginesInitialized) {
            if (!monitoringStarted) {
                monitoringStarted = true
                startMonitoring()
            }
            return
        }
        if (engineInitJob?.isActive == true) return

        engineInitJob = serviceScope.launch {
            reinitLock.withLock {
                if (!enginesInitialized) {
                    initEngines()
                    if (!isActive || !kwsLifecycle.isOpen()) return@withLock
                    enginesInitialized = true
                }
            }
            if (isActive && kwsLifecycle.isOpen() && !monitoringStarted) {
                monitoringStarted = true
                startMonitoring()
            }
        }
    }

    /** The models root under app external files dir. */
    private fun modelRoot(): String =
        (getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath) + "/models"

    private fun initEngines() {
        val modelDir = modelRoot()
        initKeywordSpotter(modelDir)
    }

    /** Wake-word (KWS) — always English (vad). No Indic keyword-spotter model exists. */
    private fun initKeywordSpotter(modelDir: String) {
        kwsLifecycle.initialize {
            var candidate: KeywordSpotterEngine? = null
            for (folder in VoiceLanguage.kwsFolders()) {
                if (!kwsLifecycle.isOpen() || !serviceJob.isActive) break
                val kws = KeywordSpotterEngine(applicationContext, "$modelDir/$folder", cacheDir?.absolutePath)
                if (kws.initialize(WakePhrases.KWS_ENTRIES) is Result.Success) {
                    candidate = kws
                    break
                }
                kws.release()
            }
            candidate
        }
        if (!kwsLifecycle.hasResource()) {
            Logger.w("VoiceService: Keyword spotter unavailable or service stopped")
        }
    }

    private val passiveLifecycle = Mutex()
    private suspend fun haltPassiveCapture(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        passiveLifecycle.withLock {
            monitoringJob?.cancel()
            recorder.stop()
            monitoringJob?.join()
            updateNotification("Foreground task active — use Stop to cancel")
            recorder.isDrainAcknowledged() && processMicrophoneLease.isIdle()
        }
    }
    private fun resumePassiveCapture() {
        serviceScope.launch {
            passiveLifecycle.withLock {
                if (!passiveHandoff.paused() && AgentRuntimeGate.isEnabled() && serviceJob.isActive) {
                    monitoringStarted = false
                    ensureEnginesAndMonitoring()
                }
            }
        }
    }

    private fun startMonitoring() {
        if (passiveHandoff.paused()) return
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                // Shared STT initializes asynchronously. Stay retryable without opening the mic.
                while (serviceScope.isActive && AgentRuntimeGate.isEnabled() &&
                    !kwsLifecycle.hasResource() && sharedVoiceModuleProvider?.invoke()?.isSttInitialized() != true) {
                    delay(500)
                }
                if (isActive && kwsLifecycle.isOpen() && AgentRuntimeGate.isEnabled()) startKeywordSpottingLoop()
            } catch (e: Exception) {
                Logger.e("VoiceService: Monitoring failed", e)
            } finally {
                monitoringStarted = false
            }
        }
    }

    private suspend fun startKeywordSpottingLoop() {
        Logger.i("VoiceService: Starting hybrid offline wake loop")
        val hasKws = kwsLifecycle.hasResource()
        // The bundled Zipformer keyword model requires at least 45 feature frames per call.
        // A 250 ms PCM chunk produces only ~19 frames and sherpa-onnx aborts natively instead of
        // returning an error (`features.cc: 0 + 45 > 19`). Keep each KWS call at 500 ms or longer.
        val chunkSizeMs = 500L

        var consecutiveSilenceChunks = 0
        val maxSilenceChunks = 2 // about one second of silence = end of command
        val commandAudio = PcmChunkAccumulator()
        val passiveWakeAudio = PcmChunkAccumulator(maxBytes = 16_000 * 2 * 8)
        // readChunk() is deliberately non-blocking and its first return after AudioRecord.start()
        // can be much shorter than the loop delay. Never pass that short startup buffer to Sherpa:
        // the native KWS decoder aborts the process when it receives fewer than 45 feature frames.
        val kwsAudio = PcmChunkAccumulator(maxBytes = KWS_SAFE_PCM_BYTES * 2)
        var passiveSpeechActive = false
        var passiveSilenceChunks = 0
        var captureIncludesWakePhrase = false
        var pausedForCall = false
        var previousStopOnly = false
        val stopAudio = PcmChunkAccumulator(maxBytes = 16_000 * 2 * 3)
        var stopSilence = 0
        var stopBufferHadPlayback = false
        var captureGeneration = GlobalTaskCancellation.generation
        var pendingCapture = captureTicket()
        var passiveCapture = pendingCapture
        var commandCapture = pendingCapture
        var stopCapture = pendingCapture

        // 0C-7: Keep recorder running continuously instead of start/stop every second.
        // Start recording ONCE and use readChunk() to drain accumulated audio incrementally.
        if (
            !VoiceCapturePolicy.isCallAudioActive(audioManager.mode) &&
            !foregroundSessionActive && !passiveHandoff.paused() &&
            !recorder.isRecording() &&
            recorder.hasPermission(this@VoiceService)
        ) {
            pendingCapture = captureTicket()
            val startResult = recorder.start(this@VoiceService)
            if (startResult is Result.Error) {
                Logger.e("VoiceService: Cannot start recorder: ${startResult.message}")
                VoiceAgentRuntime.recordError("MIC_START_FAILED", startResult.message)
                VoiceAgentRuntime.transition(VoiceAgentState.ERROR_RECOVERY, "microphone start failed")
                return
            }
            VoiceAgentRuntime.transition(VoiceAgentState.WAKE_LISTENING, "offline wake loop active")
        }

        while (serviceScope.isActive && AgentRuntimeGate.isEnabled()) {
            var iterationTrace: LatencyToken? = null
            var iterationOutcome = LatencyOutcome.REJECTED
            try {
                // Invalidate ALL retained PCM, including AudioRecord's pending chunk, before KWS/ASR.
                if (captureGeneration != GlobalTaskCancellation.generation) {
                    if (recorder.isRecording()) recorder.stop()
                    stopAudio.clear(); commandAudio.clear(); passiveWakeAudio.clear(); kwsAudio.clear()
                    stopSilence = 0; consecutiveSilenceChunks = 0; passiveSilenceChunks = 0
                    passiveSpeechActive = false; isListeningForCommand = false
                    captureIncludesWakePhrase = false; stopBufferHadPlayback = false
                    speechDetector.reset()
                    captureGeneration = GlobalTaskCancellation.generation
                    continue
                }
                // Never capture a cellular or VoIP call. Besides being a privacy boundary, call
                // audio was repeatedly decoded as wake speech and caused stale/garbled commands.
                if (VoiceCapturePolicy.isCallAudioActive(audioManager.mode)) {
                    if (recorder.isRecording()) recorder.stop()
                    stopAudio.clear()
                    stopSilence = 0
                    commandAudio.clear()
                    passiveWakeAudio.clear()
                    kwsAudio.clear()
                    passiveSpeechActive = false
                    passiveSilenceChunks = 0
                    consecutiveSilenceChunks = 0
                    isListeningForCommand = false
                    captureIncludesWakePhrase = false
                    speechDetector.reset()
                    if (!pausedForCall) {
                        pausedForCall = true
                        Logger.i("VoiceService: microphone paused while call audio is active")
                        updateNotification("Paused while a phone or voice call is active")
                        VoiceAgentRuntime.transition(VoiceAgentState.PAUSED, "call audio active")
                    }
                    delay(500)
                    continue
                } else if (pausedForCall) {
                    pausedForCall = false
                    Logger.i("VoiceService: call ended; local wake listening resumed")
                    updateNotification("Listening locally — Mic active. Say 'UnoOne' or 'Listen' to give a command.")
                    VoiceAgentRuntime.transition(VoiceAgentState.WAKE_LISTENING, "call ended")
                }

                // Exactly one audio owner at a time. Release and discard buffered state while an
                // in-app recording owns the microphone. TTS gets only the gated AEC stop path below.
                if (
                    foregroundSessionActive || passiveHandoff.paused()
                ) {
                    if (recorder.isRecording()) recorder.stop()
                    stopAudio.clear()
                    stopSilence = 0
                    commandAudio.clear()
                    passiveWakeAudio.clear()
                    kwsAudio.clear()
                    passiveSpeechActive = false
                    passiveSilenceChunks = 0
                    consecutiveSilenceChunks = 0
                    isListeningForCommand = false
                    captureIncludesWakePhrase = false
                    speechDetector.reset()
                    delay(100)
                    continue
                }

                if (!recorder.hasPermission(this@VoiceService)) {
                    delay(1000)
                    continue
                }

                // If recorder stopped (e.g., after command capture), restart it
                if (!recorder.isRecording()) {
                    pendingCapture = captureTicket()
            val startResult = recorder.start(this@VoiceService)
                    if (startResult is Result.Error) {
                        delay(500)
                        continue
                    }
                }

                if (isAgentSpeaking() && !emergencyCaptureAllowed()) {
                    recorder.stop()
                    stopAudio.clear()
                    commandAudio.clear()
                    passiveWakeAudio.clear()
                    kwsAudio.clear()
                    isListeningForCommand = false
                    updateNotification("Speech barge-in unsupported — use UI Stop")
                    delay(500)
                    continue
                }

                // Wait to accumulate audio
                delay(chunkSizeMs)

                // Read accumulated chunk without stopping the recorder
                if (captureGeneration != GlobalTaskCancellation.generation || passiveHandoff.paused()) continue
                // Metadata owns the interval BEFORE drain, including residual PCM accumulated
                // during a previous decode. Never stamp that residual with the later review.
                val chunkCapture = pendingCapture
                pendingCapture = captureTicket()
                val pcmData = recorder.readChunk()
                if (pcmData.isEmpty()) {
                    continue
                }

                // Recheck after the capture delay: never decode agent playback or call audio.
                if (!emergencyCaptureAllowed()) {
                    recorder.stop()
                    stopAudio.clear()
                    passiveWakeAudio.clear()
                    commandAudio.clear()
                    continue
                }
                val speakingDuringCapture = isAgentSpeaking()
                val stopOnly = PassiveMonitoringPolicy.stopOnly(isForegroundTaskActive(), speakingDuringCapture)
                if (stopOnly != previousStopOnly) {
                    stopAudio.clear()
                    passiveWakeAudio.clear()
                    commandAudio.clear()
                    kwsAudio.clear()
                    isListeningForCommand = false
                    passiveSpeechActive = false
                    passiveSilenceChunks = 0
                    stopSilence = 0
                    previousStopOnly = stopOnly
                    continue
                }
                if (stopOnly) {
                    if (stopAudio.size == 0) { stopBufferHadPlayback = false; stopCapture = chunkCapture }
                    stopBufferHadPlayback = stopBufferHadPlayback || speakingDuringCapture
                    val speech = hasSpeechActivity(pcmData)
                    if (speech || stopAudio.size > 0) stopAudio.add(pcmData)
                    stopSilence = if (speech) 0 else stopSilence + 1
                    if (stopAudio.size > 0 && (stopSilence >= 2 || stopAudio.isFull)) {
                        val stopComplete = !stopAudio.isFull
                        val pcm = stopAudio.toByteArray()
                        stopAudio.clear()
                        stopSilence = 0
                        val capturedPlayback = stopBufferHadPlayback
                        if (!stopCapture.isCurrent()) continue
                        stopCapture = admitTranscription(stopCapture)
                        iterationTrace = stopCapture.latencyToken
                        val text = (transcribeAudio(pcm, stopCapture) as? Result.Success)?.data
                        if (AgentRuntimeGate.isEnabled() && text != null && EmergencyStopPolicy.accepts(text,
                                capturedPlayback || isAgentSpeaking(), recorder.isEchoCancellationAvailable(),
                                recorder.isEchoCancellationEnabled(), VoiceCapturePolicy.isCallAudioActive(audioManager.mode),
                                foregroundSessionActive || passiveHandoff.paused()) &&
                                (!isForegroundTaskActive() || VoiceControlPolicy.payload(text) != text.trim())) dispatchVoice(text, stopCapture)
                        else if (UtteranceCompletionPolicy.eligible(UtteranceCompletionPolicy.endpointReason(stopComplete), text) && text != null && !capturedPlayback && !isAgentSpeaking() && emergencyCaptureAllowed() &&
                            awaitingVoiceConfirmation && stopCapture.requestId != "UNKNOWN" && stopCapture.captureStartMono > 0 &&
                            stopCapture.liveReviewId != null && stopCapture.liveReviewId == captureMetadataProvider?.invoke()?.liveReviewId &&
                            (VoiceConfirmationPolicy.decision(text, false) != null || VoiceConfirmationPolicy.decision(text, true) != null)) {
                            // Forward only a captured, still-live review decision; final request binding stays in admission.
                            dispatchVoice(text, stopCapture)
                        }
                    }
                    continue // No wake cues, ordinary command dispatch or KWS in this mode.
                }
                val sttWakeFallbackAvailable = sharedVoiceModuleProvider?.invoke()?.isSttInitialized() == true
                if (!isListeningForCommand) {
                    val hasSpeech = hasSpeechActivity(pcmData)
                    if (sttWakeFallbackAvailable && (passiveSpeechActive || hasSpeech)) {
                        if (passiveWakeAudio.size == 0) passiveCapture = chunkCapture
                        passiveSpeechActive = true
                        passiveWakeAudio.add(pcmData)
                        passiveSilenceChunks = if (hasSpeech) 0 else passiveSilenceChunks + 1
                    }

                    // Low-latency native KWS remains the first path, but only after accumulating a
                    // native-safe amount of PCM. This also covers short reads during CPU/model-load
                    // pressure, not just the first read after recorder startup.
                    kwsAudio.add(pcmData)
                    val keyword = if (hasKws && kwsAudio.size >= KWS_SAFE_PCM_BYTES) {
                        val safeKwsPcm = kwsAudio.toByteArray()
                        kwsAudio.clear()
                        kwsLifecycle.use { it.processChunk(safeKwsPcm) }
                    } else {
                        null
                    }
                    if (!serviceJob.isActive) break
                    if (!ordinarySpeechAllowed(captureGeneration)) continue
                    if (keyword != null) {
                        if (!wakeActivationGate.tryActivate(SystemClock.elapsedRealtime())) {
                            Logger.i("VoiceService: duplicate wake detection suppressed")
                            passiveWakeAudio.clear()
                            kwsAudio.clear()
                            passiveSpeechActive = false
                            passiveSilenceChunks = 0
                            continue
                        }
                        Logger.i("VoiceService: wake phrase detected by keyword spotter")
                        // Keep this speech burst. The wake word and command often arrive in one
                        // utterance; stopping here used to discard "start blind mode".
                        stopAudio.clear()
                    stopSilence = 0
                    commandAudio.clear()
                        commandCapture = if (passiveWakeAudio.size > 0) passiveCapture else chunkCapture
                        commandAudio.add(passiveWakeAudio.toByteArray())
                        if (commandAudio.size == 0) commandAudio.add(pcmData)
                        passiveWakeAudio.clear()
                        kwsAudio.clear()
                        passiveSpeechActive = false
                        passiveSilenceChunks = 0
                        captureIncludesWakePhrase = true
                        isListeningForCommand = true
                        consecutiveSilenceChunks = if (hasSpeech) 0 else 1
                        VoiceAgentRuntime.transition(VoiceAgentState.WAKE_DETECTED, "keyword spotter match")
                        updateNotification("Wake detected — capturing command...")
                    } else if (
                        passiveSpeechActive &&
                        (passiveSilenceChunks >= 2 || passiveWakeAudio.isFull)
                    ) {
                        // Some supported transducer/KWS combinations initialize but have poor live
                        // phrase recall. Decode the bounded speech burst locally and accept it only
                        // when it begins with an explicit wake phrase.
                        val wakeComplete = !passiveWakeAudio.isFull
                        val wakePcm = passiveWakeAudio.toByteArray()
                        passiveWakeAudio.clear()
                        passiveSpeechActive = false
                        passiveSilenceChunks = 0
                        if (!passiveCapture.isCurrent()) continue
                        passiveCapture = admitTranscription(passiveCapture)
                        iterationTrace = passiveCapture.latencyToken
                        val transcript = transcribeAudio(wakePcm, passiveCapture)
                        if ((transcript as? Result.Success)?.data?.let(VoiceControlPolicy::isStop) == true && emergencyCaptureAllowed()) {
                            dispatchVoice((transcript as Result.Success).data, passiveCapture)
                            continue
                        }
                        if (!ordinarySpeechAllowed(captureGeneration)) continue
                        if (!UtteranceCompletionPolicy.eligible(UtteranceCompletionPolicy.endpointReason(wakeComplete), (transcript as? Result.Success)?.data)) {
                            updateNotification(UtteranceCompletionPolicy.SHORTER_CUE)
                            continue
                        }
                        val rawTranscript = (transcript as? Result.Success)?.data
                        val match = rawTranscript?.let(WakePhraseMatcher::match)
                        if (
                            match != null &&
                            wakeActivationGate.tryActivate(SystemClock.elapsedRealtime())
                        ) {
                            VoiceAgentRuntime.recordWake(rawTranscript, match)
                            Logger.i("VoiceService: wake phrase detected by offline speech fallback")
                            onWakeWordDetected?.invoke()
                            if (match.command.isBlank()) {
                                if (recorder.isRecording()) recorder.stop()
                                if (!ordinarySpeechAllowed(captureGeneration)) continue
                                onWakeWord?.invoke()
                                if (!ordinarySpeechAllowed(captureGeneration)) continue
                                isListeningForCommand = true
                                captureIncludesWakePhrase = false
                                consecutiveSilenceChunks = 0
                                stopAudio.clear()
                    stopSilence = 0
                    commandAudio.clear()
                                VoiceAgentRuntime.transition(VoiceAgentState.COMMAND_LISTENING, "wake-only utterance")
                                updateNotification("Listening for command...")
                            } else {
                                Logger.i("VoiceService: received one-breath wake command")
                                if (recorder.isRecording()) recorder.stop()
                            dispatchVoice(match.command, passiveCapture)
                                VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "one-breath command routed")
                                updateNotification("Processing command locally...")
                            }
                        }
                    }
                } else {
                    // Retain every post-wake chunk. Previously only the final (usually silent)
                    // chunk reached STT, so wake detection succeeded but the actual command was
                    // discarded and recognition appeared random or empty.
                    if (commandAudio.size == 0) commandCapture = chunkCapture
                    commandAudio.add(pcmData)
                    // In command mode, check for silence
                    val hasSpeech = hasSpeechActivity(pcmData)

                    if (hasSpeech) {
                        consecutiveSilenceChunks = 0
                    } else {
                        consecutiveSilenceChunks++
                    }

                    // End of command when silence detected
                    if (consecutiveSilenceChunks >= maxSilenceChunks || commandAudio.isFull) {
                        // Drain final audio and stop recording
                        val finalChunk = recorder.readChunk()
                        isListeningForCommand = false
                        commandAudio.add(finalChunk)
                        val commandComplete = !commandAudio.isFull
                        val commandPcm = commandAudio.toByteArray()
                        stopAudio.clear()
                    stopSilence = 0
                    commandAudio.clear()

                        if (!commandCapture.isCurrent()) continue
                        commandCapture = admitTranscription(commandCapture)
                        iterationTrace = commandCapture.latencyToken
                        val transcript = transcribeAudio(commandPcm, commandCapture)
                        if ((transcript as? Result.Success)?.data?.let(VoiceControlPolicy::isStop) == true && emergencyCaptureAllowed()) {
                            dispatchVoice((transcript as Result.Success).data, commandCapture)
                            continue
                        }
                        if (!ordinarySpeechAllowed(captureGeneration)) continue
                        if (!UtteranceCompletionPolicy.eligible(UtteranceCompletionPolicy.endpointReason(commandComplete), (transcript as? Result.Success)?.data)) {
                            captureIncludesWakePhrase = false
                            updateNotification(UtteranceCompletionPolicy.SHORTER_CUE)
                            continue
                        }
                        if (transcript is Result.Success && transcript.data.isNotBlank()) {
                            val match = if (captureIncludesWakePhrase) {
                                WakePhraseMatcher.match(transcript.data)
                            } else {
                                null
                            }
                            if (captureIncludesWakePhrase && match == null) {
                                Logger.i("VoiceService: ignored keyword false positive")
                                captureIncludesWakePhrase = false
                                VoiceAgentRuntime.transition(
                                    VoiceAgentState.WAKE_LISTENING,
                                    "wake transcript not confirmed"
                                )
                                updateNotification("UnoOne is listening")
                                continue
                            }
                            if (match != null) {
                                VoiceAgentRuntime.recordWake(transcript.data, match)
                                onWakeWordDetected?.invoke()
                            }
                            val command = match?.command ?: WakePhrases.stripFromCommand(transcript.data)
                            if (command.isBlank()) {
                                Logger.i("VoiceService: Wake phrase detected but no command followed")
                                if (captureIncludesWakePhrase) {
                                    if (recorder.isRecording()) recorder.stop()
                                    if (!ordinarySpeechAllowed(captureGeneration)) continue
                                onWakeWord?.invoke()
                                if (!ordinarySpeechAllowed(captureGeneration)) continue
                                    isListeningForCommand = true
                                    captureIncludesWakePhrase = false
                                    consecutiveSilenceChunks = 0
                                    VoiceAgentRuntime.transition(
                                        VoiceAgentState.COMMAND_LISTENING,
                                        "wake-only utterance"
                                    )
                                    updateNotification("Listening for command...")
                                } else {
                                    VoiceAgentRuntime.transition(
                                        VoiceAgentState.WAKE_LISTENING,
                                        "empty command"
                                    )
                                    updateNotification("UnoOne is listening")
                                }
                                continue
                            }
                            captureIncludesWakePhrase = false
                            Logger.i("VoiceService: received wake command")
                            // SECURITY: Use static callback instead of broadcast Intent.
                            // sendBroadcast() is visible in system logs even with setPackage(),
                            // exposing the user's transcribed speech. The callback is set by the
                            // Application layer, keeping commands in-process only.
                            dispatchVoice(command, commandCapture)
                            VoiceAgentRuntime.transition(
                                VoiceAgentState.PROCESSING,
                                "voice command routed"
                            )
                        } else {
                            val language = sharedVoiceModuleProvider?.invoke()?.currentLanguage()
                                ?: VoiceLanguage.DEFAULT
                            VoiceAgentRuntime.recordError("STT_UNCLEAR", "retry wake listening")
                            runCatching {
                                if (ordinarySpeechAllowed(captureGeneration)) sharedVoiceModuleProvider?.invoke()?.speakAwait(
                                    VoiceLanguage.retryCue(language)
                                )
                            }
                            VoiceAgentRuntime.transition(
                                VoiceAgentState.WAKE_LISTENING,
                                "empty or failed command recognition"
                            )
                        }

                        updateNotification("UnoOne is listening")
                        // Recorder will be restarted at top of loop
                    }
                }
            } catch (e: Exception) {
                iterationOutcome = if (e is kotlinx.coroutines.CancellationException) LatencyOutcome.CANCELLED else LatencyOutcome.FAILED
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.e("VoiceService: Error in spotting loop", e)
                // 0C-11: Defensive stop on error
                if (recorder.isRecording()) {
                    recorder.stop()
                }
                delay(500)
            } finally {
                VoiceLatency.recorder.closeCapture(iterationTrace,
                    if (!serviceJob.isActive || captureGeneration != GlobalTaskCancellation.generation)
                        LatencyOutcome.CANCELLED else iterationOutcome)
            }
        }
    }

    private fun ordinarySpeechAllowed(generation: Long): Boolean = generation == GlobalTaskCancellation.generation && serviceJob.isActive && AgentRuntimeGate.isEnabled() &&
        PassiveMonitoringPolicy.ordinaryAllowed(isForegroundTaskActive(), isAgentSpeaking(), foregroundSessionActive || passiveHandoff.paused()) &&
        !VoiceCapturePolicy.isCallAudioActive(audioManager.mode)

    private fun emergencyCaptureAllowed(): Boolean = serviceJob.isActive && AgentRuntimeGate.isEnabled() &&
        PassiveMonitoringPolicy.canCapture(isAgentSpeaking(), recorder.isEchoCancellationAvailable(),
            recorder.isEchoCancellationEnabled(), VoiceCapturePolicy.isCallAudioActive(audioManager.mode),
            foregroundSessionActive || passiveHandoff.paused())

    private fun hasSpeechActivity(pcmData: ByteArray): Boolean {
        return speechDetector.hasSpeech(pcmData)
    }

    private suspend fun transcribeAudio(pcmData: ByteArray, capture: VoiceAdmissionTicket): Result<String> {
        if (!capture.isCurrent()) return Result.Error("Stale capture")
        val trace = capture.latencyToken
        VoiceLatency.recorder.mark(trace, LatencyStage.ENDPOINT_DECISION)
        val result = sharedVoiceModuleProvider?.invoke()?.transcribePcm(pcmData, trace)
            ?: Result.Error("Shared offline STT is unavailable")
        if (!capture.isCurrent()) {
            VoiceLatency.recorder.closeCapture(trace, LatencyOutcome.CANCELLED)
            return Result.Error("Stale capture")
        }
        if (result is Result.Error) VoiceLatency.recorder.closeCapture(trace, LatencyOutcome.FAILED)
        // Successful dispatch transfers the trace to the task. Decode completion is NOT task success.
        return result
    }

    private fun injectedTicket(intent: Intent, text: String, generation: Long): VoiceAdmissionTicket {
        // Missing legacy extras stay UNKNOWN. No provider call at delayed Intent delivery.
        val requestId = intent.getStringExtra("voice_request_id") ?: "UNKNOWN"
        val evidencePackage = intent.getStringExtra("voice_app_package")
        val evidence = evidencePackage?.let { com.unoone.agent.core.voice.UnderlyingAppEvidence(
            it, intent.getIntExtra("voice_app_window", -1), intent.getLongExtra("voice_app_observed", 0L),
            intent.getBooleanExtra("voice_app_is_application", false), intent.getBooleanExtra("voice_app_is_overlay", true)) }
        return VoiceAdmissionTicket(text, generation, requestId,
            intent.getLongExtra("voice_capture_start", 0L), evidence,
            intent.getStringExtra("voice_review_id"), VoiceLatency.recorder.forRequest(requestId))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UnoOne Voice Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background listener for hands-free commands"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String = "Listening locally — Mic active. Say 'UnoOne' or 'Listen' to give a command."): Notification {
        // User-perceptible microphone FGS notification (Play policy): makes background mic capture
        // explicit and gives the user a visible, ongoing signal. No silent background voice mode.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .addAction(android.R.drawable.ic_media_pause, "Stop", android.app.PendingIntent.getBroadcast(
                this, 701, Intent("com.unoone.agent.STOP_ALL_TASKS").setClassName(packageName, "com.unoone.agent.TaskStopReceiver"),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .setContentTitle("UnoOne listening locally")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        val registered = liveService.get()
        if (registered?.get() === this) liveService.compareAndSet(registered, null)
        kwsLifecycle.invalidate()
        engineInitJob?.cancel()
        monitoringJob?.cancel()
        // Cancel the SupervisorJob so any stray child coroutine on serviceScope can't outlive the service.
        serviceJob.cancel()
        // Independent scope: cancellation is only a request, JNI constructors/decodes may ignore
        // it. Join all service children off Main before touching native or recorder ownership.
        CoroutineScope(Dispatchers.IO).launch {
            serviceJob.join()
            try {
                if (recorder.isRecording()) recorder.stop()
            } finally {
                kwsLifecycle.close()
            }
        }
        wakeActivationGate.reset()
        VoiceAgentRuntime.transition(
            if (AgentRuntimeGate.isEnabled()) VoiceAgentState.PAUSED else VoiceAgentState.DISABLED,
            "voice service stopped"
        )
        Logger.i("VoiceService: Stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // START_STICKY handles ordinary system recreation. Calling startForegroundService() from
        // this background callback is rejected for microphone services on Android 14+ and left a
        // dead ServiceRecord on HyperOS. MainActivity resume and a bubble tap are the legal,
        // user-visible recovery points.
        Logger.i("VoiceService: task removed; awaiting sticky or visible-UI recovery")
        super.onTaskRemoved(rootIntent)
    }
}
