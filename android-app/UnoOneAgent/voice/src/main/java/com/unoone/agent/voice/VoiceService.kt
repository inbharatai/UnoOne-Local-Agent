package com.unoone.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.KeywordSpotterEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.tts.SherpaTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

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
    private var keywordSpotter: KeywordSpotterEngine? = null
    private var sttEngine: SherpaSttEngine? = null
    private var ttsEngine: SherpaTtsEngine? = null
    private var androidStt: AndroidSttEngine? = null
    private var useAndroidStt = false

    /**
     * When true, the emergency Android SpeechRecognizer is used when Sherpa STT is unavailable.
     * Default false (offline-first). Mirrors [com.unoone.agent.voice.VoiceModule.allowSystemSttFallback]
     * so the service never silently uses the cloud-dependent system recognizer.
     */
    private var allowSystemSttFallback = false

    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null

    @Volatile
    private var isListeningForCommand = false

    companion object {
        private const val CHANNEL_ID = "voice_service_channel"
        private const val NOTIFICATION_ID = 1001
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
        var onWakeWord: (() -> Unit)? = null

        /**
         * C5: when true, the in-app hands-free session owns the mic — the background KWS loop must
         * release its recorder and skip spotting so the two AudioRecord instances don't contend
         * (the prior "listen is slow/erratic" cause). Set by AgentViewModel when a session starts.
         */
        var foregroundSessionActive: Boolean = false

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
        Logger.i("VoiceService: Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRuntimeGate.isEnabled()) {
            if (recorder.isRecording()) recorder.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REINIT_LANG) {
            // Language changed at runtime: rebuild STT/TTS only, keep the wake-word loop running.
            // MUST run off the main thread — Sherpa model load (especially the larger Indic Whisper
            // ASR models) is heavy I/O and blocks onStartCommand's main thread, freezing the UI/ANR.
            serviceScope.launch { reinitSttTts() }
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

    /** The currently selected voice language (normalized; default English). */
    private fun currentLanguage(): String =
        VoiceLanguage.normalize(
            getSharedPreferences(VoiceLanguage.PREF_NAME, Context.MODE_PRIVATE)
                .getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
        )

    private fun initEngines() {
        val modelDir = modelRoot()
        val lang = currentLanguage()
        initSttTts(modelDir, lang)
        initKeywordSpotter(modelDir)
    }

    /** Builds STT + TTS for [lang]. Sherpa is the offline default; Android STT is emergency-only. */
    private fun initSttTts(modelDir: String, lang: String) {
        val asr = VoiceLanguage.asrSpec(lang)
        val stt = SherpaSttEngine(this, "$modelDir/${asr.folder}", asr.mode, asr.language)
        if (stt.initialize() is Result.Success) {
            sttEngine = stt
            useAndroidStt = false
            Logger.i("VoiceService: Sherpa STT ready (offline, ${asr.mode}/$lang)")
        } else {
            useAndroidStt = allowSystemSttFallback
            Logger.w("VoiceService: Sherpa STT unavailable (lang=$lang); emergency Android fallback ${if (allowSystemSttFallback) "enabled" else "disabled"}")
        }

        val tts = SherpaTtsEngine(this, "$modelDir/${VoiceLanguage.ttsFolder(lang)}")
        if (tts.initialize() is Result.Success) {
            ttsEngine = tts
            Logger.i("VoiceService: Sherpa TTS ready (offline, $lang)")
        } else {
            Logger.w("VoiceService: Sherpa TTS unavailable (lang=$lang)")
        }
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

    /** Releases and rebuilds STT/TTS for the current language pref; keeps KWS running. */
    private suspend fun reinitSttTts() {
        reinitLock.withLock {
            runCatching { sttEngine?.release() }
            sttEngine = null
            runCatching { ttsEngine?.release() }
            ttsEngine = null
            val lang = currentLanguage()
            Logger.i("VoiceService: reinitializing STT/TTS for language '$lang'")
            initSttTts(modelRoot(), lang)
        }
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                if (keywordSpotter != null || sttEngine != null) {
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
        val sttWakeFallbackAvailable = sttEngine != null
        if (kws == null && !sttWakeFallbackAvailable) return
        val chunkSizeMs = 1000L // Process 1-second chunks

        var consecutiveSilenceChunks = 0
        val maxSilenceChunks = 3 // 3 seconds of silence = end of command
        val commandAudio = PcmChunkAccumulator()
        val passiveWakeAudio = PcmChunkAccumulator(maxBytes = 16_000 * 2 * 8)
        var passiveSpeechActive = false
        var passiveSilenceChunks = 0

        // 0C-7: Keep recorder running continuously instead of start/stop every second.
        // Start recording ONCE and use readChunk() to drain accumulated audio incrementally.
        if (!recorder.isRecording() && recorder.hasPermission(this@VoiceService)) {
            val startResult = recorder.start(this@VoiceService)
            if (startResult is Result.Error) {
                Logger.e("VoiceService: Cannot start recorder: ${startResult.message}")
                return
            }
        }

        while (serviceScope.isActive && AgentRuntimeGate.isEnabled()) {
            try {
                // C5: single mic owner — when the in-app hands-free session is active, release our
                // recorder and skip spotting so it doesn't contend for the mic. Resume when it ends.
                if (foregroundSessionActive) {
                    if (recorder.isRecording()) recorder.stop()
                    delay(500)
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

                    // Low-latency native KWS remains the first path.
                    val keyword = kws?.processChunk(pcmData)
                    if (keyword != null) {
                        Logger.i("VoiceService: wake phrase detected by keyword spotter")
                        isListeningForCommand = true
                        consecutiveSilenceChunks = 0
                        // Retain the whole speech burst because the command may follow the wake phrase
                        // in one breath and may have begun before the one-second KWS chunk completed.
                        commandAudio.clear()
                        commandAudio.add(passiveWakeAudio.toByteArray())
                        if (commandAudio.size == 0) commandAudio.add(pcmData)
                        passiveWakeAudio.clear()
                        passiveSpeechActive = false
                        passiveSilenceChunks = 0
                        onWakeWordDetected?.invoke()
                        // Eyes-free (WS2): speak the "I'm listening" cue. Invoked via the static
                        // callback so the Application can route it to the shared VoiceModule without
                        // cross-module coupling, and dispatched off the audio thread by the caller so
                        // the cue does not block command capture.
                        onWakeWord?.invoke()

                        // Update notification
                        updateNotification("Listening for command...")
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
                        val command = (transcript as? Result.Success)
                            ?.data
                            ?.let(WakePhrases::commandAfterWakePhrase)
                        if (command != null) {
                            Logger.i("VoiceService: wake phrase detected by offline speech fallback")
                            onWakeWordDetected?.invoke()
                            onWakeWord?.invoke()
                            if (command.isBlank()) {
                                isListeningForCommand = true
                                consecutiveSilenceChunks = 0
                                commandAudio.clear()
                                updateNotification("Listening for command...")
                            } else {
                                Logger.i("VoiceService: received one-breath wake command")
                                onCommandReceived?.invoke(command)
                                voiceCommandCallback?.invoke(command)
                                updateNotification("UnoOne is listening")
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
                            val command = WakePhrases.stripFromCommand(transcript.data)
                            if (command.isBlank()) {
                                Logger.i("VoiceService: Wake phrase detected but no command followed")
                                updateNotification("UnoOne is listening")
                                continue
                            }
                            Logger.i("VoiceService: received wake command")
                            onCommandReceived?.invoke(command)

                            // SECURITY: Use static callback instead of broadcast Intent.
                            // sendBroadcast() is visible in system logs even with setPackage(),
                            // exposing the user's transcribed speech. The callback is set by the
                            // Application layer, keeping commands in-process only.
                            voiceCommandCallback?.invoke(command)
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
        // Sherpa offline path when available.
        if (sttEngine != null) return sttEngine!!.transcribe(pcmData)
        // Emergency Android fallback — only when explicitly opted in. Never silently use the
        // cloud-dependent system SpeechRecognizer; otherwise surface the missing-model state so
        // the caller can prompt the user instead of producing a phantom transcript.
        if (!allowSystemSttFallback) {
            return Result.Error("Offline STT model not installed. Install the Sherpa ASR model or enable the system fallback in Settings.")
        }
        val engine = androidStt ?: AndroidSttEngine(this).also { androidStt = it }
        val initResult = engine.initialize()
        if (initResult is Result.Error) return initResult
        return engine.transcribeOnce(
            Locale.forLanguageTag(VoiceLanguage.localeTag(currentLanguage()))
        )
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
        sttEngine?.release()
        ttsEngine?.release()
        keywordSpotter?.release()
        androidStt?.release()
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
