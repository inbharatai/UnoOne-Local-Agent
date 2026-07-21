package com.unoone.agent.voice

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

    private val recorder = AudioRecorder()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var keywordSpotter: KeywordSpotterEngine? = null
    private val wakeActivationGate = WakeActivationGate()

    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null

    @Volatile
    private var isListeningForCommand = false

    companion object {
        private const val CHANNEL_ID = "voice_service_channel"
        private const val NOTIFICATION_ID = 1001
        /** One second of 16 kHz mono PCM16; safely exceeds the bundled KWS 45-frame minimum. */
        private const val KWS_SAFE_PCM_BYTES = 16_000 * 2
        const val ACTION_VOICE_COMMAND = "com.unoone.agent.VOICE_COMMAND"
        const val EXTRA_COMMAND = "command"
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

        private val agentSpeechOwners = AtomicInteger(0)
        private val foregroundTaskOwners = AtomicInteger(0)

        /** Prevents the wake recorder from transcribing UnoOne's own TTS. */
        fun beginAgentSpeech() {
            agentSpeechOwners.incrementAndGet()
        }

        fun endAgentSpeech() {
            agentSpeechOwners.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }

        fun isAgentSpeaking(): Boolean = agentSpeechOwners.get() > 0

        /**
         * Gives a foreground agent operation exclusive access to local CPU/audio resources.
         * The passive wake loop otherwise runs full Sherpa fallback transcription every few
         * seconds, competing with PageAgent inference and decoding the agent's spoken progress.
         */
        fun beginForegroundTask() {
            foregroundTaskOwners.incrementAndGet()
        }

        fun endForegroundTask() {
            foregroundTaskOwners.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }

        fun isForegroundTaskActive(): Boolean = foregroundTaskOwners.get() > 0

        fun clearAudioOwnership() {
            foregroundSessionActive = false
            agentSpeechOwners.set(0)
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
            if (!command.isNullOrBlank()) {
                Logger.i("VoiceService: received injected voice command")
                voiceCommandCallback?.invoke(command)
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
                    enginesInitialized = true
                }
            }
            if (!monitoringStarted) {
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
        for ((index, folder) in VoiceLanguage.kwsFolders().withIndex()) {
            val kws = KeywordSpotterEngine(this, "$modelDir/$folder", cacheDir?.absolutePath)
            if (kws.initialize(WakePhrases.KWS_ENTRIES) is Result.Success) {
                keywordSpotter = kws
                if (index > 0) {
                    Logger.i("VoiceService: using installed English ASR files for wake-word fallback")
                }
                Logger.i("VoiceService: Keyword spotter ready (English wake words: ${WakePhrases.LIST})")
                return
            }
            kws.release()
        }
        keywordSpotter = null
        Logger.w("VoiceService: Keyword spotter unavailable; wake phrases are disabled until a compatible English transducer is installed")
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                if (keywordSpotter != null ||
                    sharedVoiceModuleProvider?.invoke()?.isSttInitialized() == true
                ) {
                    startKeywordSpottingLoop()
                } else {
                    Logger.i("VoiceService: No offline wake or speech model; manual activation only")
                }
            } catch (e: Exception) {
                Logger.e("VoiceService: Monitoring failed", e)
            }
        }
    }

    private suspend fun startKeywordSpottingLoop() {
        Logger.i("VoiceService: Starting hybrid offline wake loop")
        val kws = keywordSpotter
        val sttWakeFallbackAvailable =
            sharedVoiceModuleProvider?.invoke()?.isSttInitialized() == true
        if (kws == null && !sttWakeFallbackAvailable) return
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

        // 0C-7: Keep recorder running continuously instead of start/stop every second.
        // Start recording ONCE and use readChunk() to drain accumulated audio incrementally.
        if (
            !VoiceCapturePolicy.isCallAudioActive(audioManager.mode) &&
            !recorder.isRecording() &&
            recorder.hasPermission(this@VoiceService)
        ) {
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
            try {
                // Never capture a cellular or VoIP call. Besides being a privacy boundary, call
                // audio was repeatedly decoded as wake speech and caused stale/garbled commands.
                if (VoiceCapturePolicy.isCallAudioActive(audioManager.mode)) {
                    if (recorder.isRecording()) recorder.stop()
                    commandAudio.clear()
                    passiveWakeAudio.clear()
                    kwsAudio.clear()
                    passiveSpeechActive = false
                    passiveSilenceChunks = 0
                    consecutiveSilenceChunks = 0
                    isListeningForCommand = false
                    captureIncludesWakePhrase = false
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
                // in-app recording or UnoOne TTS owns audio, otherwise the wake service can hear the
                // app's own reply and replay an old/partial command.
                if (foregroundSessionActive || isAgentSpeaking() || isForegroundTaskActive()) {
                    if (recorder.isRecording()) recorder.stop()
                    commandAudio.clear()
                    passiveWakeAudio.clear()
                    kwsAudio.clear()
                    passiveSpeechActive = false
                    passiveSilenceChunks = 0
                    consecutiveSilenceChunks = 0
                    isListeningForCommand = false
                    captureIncludesWakePhrase = false
                    delay(100)
                    continue
                }

                if (!recorder.hasPermission(this@VoiceService)) {
                    delay(1000)
                    continue
                }

                // If recorder stopped (e.g., after command capture), restart it
                if (!recorder.isRecording()) {
                    val startResult = recorder.start(this@VoiceService)
                    if (startResult is Result.Error) {
                        delay(500)
                        continue
                    }
                }

                // Wait to accumulate audio
                delay(chunkSizeMs)

                // Read accumulated chunk without stopping the recorder
                val pcmData = recorder.readChunk()
                if (pcmData.isEmpty()) {
                    continue
                }

                if (!isListeningForCommand) {
                    val hasSpeech = hasSpeechActivity(pcmData)
                    if (sttWakeFallbackAvailable && (passiveSpeechActive || hasSpeech)) {
                        passiveSpeechActive = true
                        passiveWakeAudio.add(pcmData)
                        passiveSilenceChunks = if (hasSpeech) 0 else passiveSilenceChunks + 1
                    }

                    // Low-latency native KWS remains the first path, but only after accumulating a
                    // native-safe amount of PCM. This also covers short reads during CPU/model-load
                    // pressure, not just the first read after recorder startup.
                    kwsAudio.add(pcmData)
                    val keyword = if (kws != null && kwsAudio.size >= KWS_SAFE_PCM_BYTES) {
                        val safeKwsPcm = kwsAudio.toByteArray()
                        kwsAudio.clear()
                        kws.processChunk(safeKwsPcm)
                    } else {
                        null
                    }
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
                        commandAudio.clear()
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
                        val wakePcm = passiveWakeAudio.toByteArray()
                        passiveWakeAudio.clear()
                        passiveSpeechActive = false
                        passiveSilenceChunks = 0
                        val transcript = transcribeAudio(wakePcm)
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
                                onWakeWord?.invoke()
                                isListeningForCommand = true
                                captureIncludesWakePhrase = false
                                consecutiveSilenceChunks = 0
                                commandAudio.clear()
                                VoiceAgentRuntime.transition(VoiceAgentState.COMMAND_LISTENING, "wake-only utterance")
                                updateNotification("Listening for command...")
                            } else {
                                Logger.i("VoiceService: received one-breath wake command")
                                if (recorder.isRecording()) recorder.stop()
                                onCommandReceived?.invoke(match.command)
                                voiceCommandCallback?.invoke(match.command)
                                VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "one-breath command routed")
                                updateNotification("Processing command locally...")
                            }
                        }
                    }
                } else {
                    // Retain every post-wake chunk. Previously only the final (usually silent)
                    // chunk reached STT, so wake detection succeeded but the actual command was
                    // discarded and recognition appeared random or empty.
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
                        val commandPcm = commandAudio.toByteArray()
                        commandAudio.clear()

                        val transcript = transcribeAudio(commandPcm)
                        if (transcript is Result.Success) {
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
                                    onWakeWord?.invoke()
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
                            onCommandReceived?.invoke(command)

                            // SECURITY: Use static callback instead of broadcast Intent.
                            // sendBroadcast() is visible in system logs even with setPackage(),
                            // exposing the user's transcribed speech. The callback is set by the
                            // Application layer, keeping commands in-process only.
                            voiceCommandCallback?.invoke(command)
                            VoiceAgentRuntime.transition(
                                VoiceAgentState.PROCESSING,
                                "voice command routed"
                            )
                        }

                        updateNotification("UnoOne is listening")
                        // Recorder will be restarted at top of loop
                    }
                }
            } catch (e: Exception) {
                Logger.e("VoiceService: Error in spotting loop", e)
                // 0C-11: Defensive stop on error
                if (recorder.isRecording()) {
                    recorder.stop()
                }
                delay(500)
            }
        }
    }

    private fun hasSpeechActivity(pcmData: ByteArray): Boolean {
        // Simple energy-based VAD: check if RMS exceeds threshold
        if (pcmData.size < 2) return false
        var sum = 0.0
        for (i in 0 until pcmData.size - 1 step 2) {
            // Little-endian signed 16-bit: mask the high byte to avoid sign-extension corruption.
            val sample = (pcmData[i].toInt() and 0xFF) or ((pcmData[i + 1].toInt() and 0xFF) shl 8)
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / (pcmData.size / 2))
        return rms > 500 // Threshold for speech detection
    }

    private suspend fun transcribeAudio(pcmData: ByteArray): Result<String> {
        return sharedVoiceModuleProvider?.invoke()?.transcribePcm(pcmData)
            ?: Result.Error("Shared offline STT is unavailable")
    }

    private fun sqrt(x: Double): Double = kotlin.math.sqrt(x)

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
            .setContentTitle("UnoOne listening locally")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        engineInitJob?.cancel()
        monitoringJob?.cancel()
        // Cancel the SupervisorJob so any stray child coroutine on serviceScope can't outlive the service.
        serviceJob.cancel()
        // 0C-11: Defensive stop — ensure recorder is always released even if
        // an exception interrupted the monitoring loop before reaching recorder.stop()
        if (recorder.isRecording()) {
            recorder.stop()
        }
        keywordSpotter?.release()
        wakeActivationGate.reset()
        VoiceAgentRuntime.transition(
            if (AgentRuntimeGate.isEnabled()) VoiceAgentState.PAUSED else VoiceAgentState.DISABLED,
            "voice service stopped"
        )
        Logger.i("VoiceService: Stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service if killed by aggressive battery optimization (Xiaomi, Huawei, Oppo, etc.)
        if (AgentRuntimeGate.isEnabled()) {
            val restartIntent = Intent(this, VoiceService::class.java)
            startForegroundService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }
}
