package com.unoone.agent.voice

import android.content.Context
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.errorOrNull
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.stt.SttMode
import com.unoone.agent.voice.tts.SherpaTtsEngine
import com.unoone.agent.voice.tts.TtsPlayer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Which STT/TTS runtime is active. Used by the offline-mode indicator and the voice test screen
 * to tell the user whether they are running fully offline (SHERPA), on the emergency system
 * fallback (SYSTEM_FALLBACK — not truly offline), or have no engine (UNAVAILABLE).
 */
enum class VoiceRuntimeState { SHERPA, SYSTEM_FALLBACK, UNAVAILABLE }

class VoiceModule(private val context: Context) {

    private val recorder = AudioRecorder()
    @Volatile private var sttEngine: SherpaSttEngine? = null
    @Volatile private var ttsEngine: SherpaTtsEngine? = null
    @Volatile private var activeSttKey: String = ""
    @Volatile private var activeTtsKey: String = ""
    @Volatile private var androidStt: AndroidSttEngine? = null
    private val ttsPlayer = TtsPlayer(context)

    // Sherpa is the default. The Android system SpeechRecognizer is used ONLY as an explicit,
    // opt-in emergency fallback — never silently. This keeps the "fully offline" promise honest.
    @Volatile private var useAndroidStt = false

    /**
     * When true, the user has opted in to the emergency Android SpeechRecognizer fallback for when
     * the Sherpa STT model is not installed. Default false (offline-first). Set from Settings.
     */
    @Volatile
    var allowSystemSttFallback: Boolean = false

    @Volatile
    var sttState: VoiceRuntimeState = VoiceRuntimeState.UNAVAILABLE
        private set

    @Volatile
    var ttsState: VoiceRuntimeState = VoiceRuntimeState.UNAVAILABLE
        private set

    /** Confidence of the last STT result (0..1). 0 when empty/failed/uninitialized. Drives the retry prompt. */
    @Volatile
    var lastSttConfidence: Float = 0f
        private set

    private val captureOwnership = CaptureOwnership()
    private val legacyCaptureOwner = Any()

    private val activeSttJob = AtomicReference<Deferred<Result<String>>?>(null)
    private val isRecordingFlag = AtomicBoolean(false)
    private val amplitudeListeners = CopyOnWriteArraySet<(Float) -> Unit>()

    @Volatile
    private var primaryAmplitudeListener: ((Float) -> Unit)? = null

    init {
        // Initialize the universal Android TTS player immediately (used as the emergency TTS path).
        ttsPlayer.initialize(context)
        recorder.onAmplitude = ::dispatchAmplitude
    }

    var onAmplitude: ((Float) -> Unit)?
        get() = primaryAmplitudeListener
        set(value) {
            primaryAmplitudeListener = value
        }

    /**
     * Floating UI and the main Agent screen share one recorder. Additional listeners let the
     * overlay observe end-of-speech without replacing the AgentViewModel waveform callback.
     */
    fun addAmplitudeListener(listener: (Float) -> Unit) {
        amplitudeListeners += listener
    }

    fun removeAmplitudeListener(listener: (Float) -> Unit) {
        amplitudeListeners -= listener
    }

    private fun dispatchAmplitude(amplitude: Float) {
        primaryAmplitudeListener?.invoke(amplitude)
        amplitudeListeners.forEach { listener -> listener(amplitude) }
    }

    /**
     * Initialize Sherpa STT for [modelDir] using the given [mode] and whisper [language].
     * Defaults match the English streaming transducer so existing single-arg callers are unchanged.
     */
    fun initStt(
        modelDir: String,
        mode: SttMode = SttMode.TRANSDUCER,
        language: String = "en"
    ): Result<Unit> {
        val engine = SherpaSttEngine(context, modelDir, mode, language)
        val result = engine.initialize()
        return if (result is Result.Success) {
            sttEngine = engine
            activeSttKey = sttKey(modelDir, mode, language)
            useAndroidStt = false
            sttState = VoiceRuntimeState.SHERPA
            Logger.i("VoiceModule: Using Sherpa-ONNX for STT (offline, $mode/$language)")
            result
        } else {
            // Do NOT silently flip to Android STT. Surface the missing-model state so the UI can
            // prompt the user to install the model (or opt into the emergency system fallback).
            sttEngine = null
            activeSttKey = ""
            sttState = if (allowSystemSttFallback) VoiceRuntimeState.SYSTEM_FALLBACK else VoiceRuntimeState.UNAVAILABLE
            useAndroidStt = allowSystemSttFallback
            Logger.w("VoiceModule: Sherpa STT unavailable (${result.errorOrNull()}); system fallback ${if (allowSystemSttFallback) "enabled" else "disabled"}")
            result
        }
    }

    /**
     * Ensure bilingual STT and the selected reply-language TTS are initialized.
     *
     * Input recognition is deliberately independent from [lang]. Switching English/Hindi therefore
     * replaces only TTS and never tears down/reloads the 300M bilingual recognizer.
     */
    @Synchronized
    fun reinitForLanguage(modelBaseDir: String, lang: String = currentLanguage()): Pair<Result<Unit>, Result<Unit>> {
        val asr = VoiceLanguage.inputAsrSpec()
        val sttRoot = "$modelBaseDir/${asr.folder}"
        val desiredSttKey = sttKey(sttRoot, asr.mode, asr.language)
        val sttResult = if (sttEngine != null && activeSttKey == desiredSttKey) {
            Result.Success(Unit)
        } else {
            runCatching { sttEngine?.release() }
            sttEngine = null
            activeSttKey = ""
            initStt(sttRoot, asr.mode, asr.language)
        }

        val normalizedLanguage = VoiceLanguage.normalize(lang)
        val ttsRoot = "$modelBaseDir/${VoiceLanguage.ttsFolder(normalizedLanguage)}"
        val ttsResult = if (ttsEngine != null && activeTtsKey == ttsRoot) {
            Result.Success(Unit)
        } else {
            runCatching { ttsEngine?.release() }
            ttsEngine = null
            activeTtsKey = ""
            initTts(ttsRoot)
        }
        return sttResult to ttsResult
    }

    /** The currently selected voice language from SharedPreferences (normalized, default English). */
    fun currentLanguage(): String =
        VoiceLanguage.normalize(
            context.getSharedPreferences(VoiceLanguage.PREF_NAME, android.content.Context.MODE_PRIVATE)
                .getString(VoiceLanguage.PREF_KEY, VoiceLanguage.DEFAULT)
        )

    fun initTts(modelDir: String): Result<Unit> {
        val engine = SherpaTtsEngine(context, modelDir)
        val result = engine.initialize()
        return if (result is Result.Success) {
            ttsEngine = engine
            activeTtsKey = modelDir
            ttsState = VoiceRuntimeState.SHERPA
            Logger.i("VoiceModule: Using Sherpa-ONNX for TTS (offline)")
            result
        } else {
            // TTS keeps a graceful Android fallback so the agent can still speak without a model —
            // but this is explicitly marked as the emergency system fallback, not the default path.
            ttsEngine = null
            activeTtsKey = ""
            ttsState = VoiceRuntimeState.SYSTEM_FALLBACK
            Logger.w("VoiceModule: Sherpa TTS unavailable (${result.errorOrNull()}); using Android TTS fallback")
            result
        }
    }

    private fun callActive(): Boolean {
        val mode = context.getSystemService(android.media.AudioManager::class.java)?.mode ?: return true
        return mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    fun startRecording(context: Context, scope: CoroutineScope): Result<Unit> =
        captureOwnership.acquire(legacyCaptureOwner, { stopRecordingNative(); Unit }) { startRecordingNative(context, scope) }

    /** The scope, capture and STT child all end before the calling task releases its slot. */
    suspend fun recordOwned(context: Context, durationMillis: Long): Result<String> = coroutineScope {
        val owner = Any()
        var job: Deferred<Result<String>>? = null
        try {
            val start = captureOwnership.acquire(owner, { stopRecordingNative(); Unit }) {
                try { startRecordingNative(context, this) }
                finally { job = activeSttJob.get() }
            }
            if (start is Result.Error) return@coroutineScope start
            delay(durationMillis)
            stopAndTranscribeOwned(owner)
        } finally {
            withContext(NonCancellable) {
                try {
                    captureOwnership.matching(owner) { stopRecordingNative() }
                } finally {
                    try {
                        job?.cancelAndJoin()
                        // Android cancellation posts native recognizer destruction to Main.
                        if (job != null) withContext(Dispatchers.Main) { Unit }
                    }
                    finally { captureOwnership.release(owner) }
                }
            }
        }
    }

    private fun startRecordingNative(context: Context, scope: CoroutineScope): Result<Unit> {
        if (callActive()) return Result.Error("Voice capture unavailable during a call")
        if (!AgentRuntimeGate.isEnabled()) {
            return Result.Error("UnoOne is disabled. Enable it before using the microphone.")
        }
        if (!isRecordingFlag.compareAndSet(false, true)) return Result.Success(Unit)

        // Sherpa path (offline): record PCM, transcribe on stop.
        // Emergency Android path: the recognizer records its own audio; we drive it via async.
        return if (useAndroidStt && sttEngine == null) {
            if (!allowSystemSttFallback) {
                isRecordingFlag.set(false)
                return Result.Error("Offline STT model not installed. Install the Sherpa ASR model or enable the system fallback in Settings.")
            }
            val engine = androidStt ?: AndroidSttEngine(context).also { androidStt = it }
            val initResult = engine.initialize()
            if (initResult is Result.Error) {
                isRecordingFlag.set(false)
                return initResult
            }

            activeSttJob.set(scope.async(Dispatchers.Main) {
                engine.transcribeOnce(
                    locale = Locale.forLanguageTag(VoiceLanguage.localeTag(currentLanguage())),
                    onAmplitude = ::dispatchAmplitude
                )
            })
            Result.Success(Unit)
        } else {
            // Sherpa offline path. If the Sherpa engine isn't initialized and the emergency Android
            // fallback is disabled, refuse to record — otherwise stopAndTranscribe would NPE on
            // sttEngine!! and silently recording with no way to transcribe wastes the capture.
            if (sttEngine == null) {
                isRecordingFlag.set(false)
                return Result.Error("Offline STT model not installed. Install the Sherpa ASR model or enable the system fallback in Settings.")
            }
            if (!recorder.hasPermission(context)) {
                isRecordingFlag.set(false)
                return Result.Error("Microphone permission not granted")
            }
            val result = recorder.start(context)
            if (result is Result.Error) {
                isRecordingFlag.set(false)
            }
            result
        }
    }

    suspend fun stopAndTranscribe(): Result<String> {
        try { return stopAndTranscribeOwned(legacyCaptureOwner) }
        finally {
            withContext(NonCancellable) {
                val job = captureOwnership.matching(legacyCaptureOwner) {
                    val pending = activeSttJob.get()
                    stopRecordingNative()
                    pending
                }
                try {
                        job?.cancelAndJoin()
                        // Android cancellation posts native recognizer destruction to Main.
                        if (job != null) withContext(Dispatchers.Main) { Unit }
                    }
                finally { captureOwnership.release(legacyCaptureOwner) }
            }
        }
    }

    private suspend fun stopAndTranscribeOwned(owner: Any): Result<String> {
        var job: Deferred<Result<String>>? = null
        var pcm = ByteArray(0)
        var engine: SherpaSttEngine? = null
        val admitted = captureOwnership.matching(owner) {
            if (!isRecordingFlag.getAndSet(false)) false
            else {
                engine = sttEngine
                job = activeSttJob.get()
                if (job != null) androidStt?.stopListening()
                else pcm = recorder.stop()
                true
            }
        } == true
        if (!admitted) return Result.Error("No matching active voice capture session")
        if (callActive()) return Result.Error("Voice capture discarded during call")
        VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "transcribing final utterance")
        val res = if (job != null) job!!.await() else withContext(Dispatchers.IO) {
            val decoder = engine ?: return@withContext Result.Error("Offline STT model not installed")
            if (pcm.isEmpty()) return@withContext Result.Error("No audio captured")
            val started = System.currentTimeMillis()
            decoder.transcribe(pcm).also {
                com.unoone.agent.observability.Diagnostics.recordSttLatency(System.currentTimeMillis() - started)
            }
        }
        if (callActive()) return Result.Error("Voice capture discarded during call")
        lastSttConfidence = if (res is Result.Success && res.data.isNotBlank()) engine?.lastConfidence ?: 1f else 0f
        return res
    }

    /**
     * Decode PCM captured by the background wake service through this application-owned Sherpa
     * engine. VoiceService no longer constructs a second full STT model.
     */
    suspend fun transcribePcm(pcmData: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        if (callActive()) return@withContext Result.Error("Voice capture unavailable during call")
        if (pcmData.isEmpty()) return@withContext Result.Error("No audio captured")
        val engine = sttEngine
            ?: return@withContext Result.Error("Offline STT model not installed")
        val result = engine.transcribe(pcmData)
        if (callActive()) return@withContext Result.Error("Voice capture discarded during call")
        lastSttConfidence = if (result is Result.Success) engine.lastConfidence else 0f
        result
    }

    fun stopRecording(): ByteArray = captureOwnership.stopAll { stopRecordingNative() }

    private fun stopRecordingNative(): ByteArray {
        isRecordingFlag.set(false)
        // Cancel the active STT job to prevent orphaned coroutines
        activeSttJob.getAndSet(null)?.cancel()
        return recorder.stop()
    }

    /**
     * Highly accurate, multilingual on-device transcription supporting English and Indian languages.
     * Emergency-only path; the default offline path is Sherpa via [stopAndTranscribe].
     */
    suspend fun transcribeWithAndroid(locale: Locale = Locale("en", "IN")): Result<String> {
        return withContext(Dispatchers.Main) {
            val engine = androidStt ?: AndroidSttEngine(context).also { androidStt = it }
            val initResult = engine.initialize()
            if (initResult is Result.Error) return@withContext initResult
            engine.transcribeOnce(locale, ::dispatchAmplitude)
        }
    }

    /**
     * Speak text. Uses Sherpa offline TTS when available; otherwise the emergency Android TTS.
     */
    fun speak(
        text: String,
        languageCode: String = VoiceLanguage.localeTag(currentLanguage())
    ): Result<Unit> {
        if (!AgentRuntimeGate.isEnabled()) return Result.Error("UnoOne is disabled")
        val engine = ttsEngine
        if (engine != null && engine.isInitialized()) {
            val ttsStart = System.currentTimeMillis()
            val res = engine.speak(text)
            com.unoone.agent.observability.Diagnostics.recordTtsLatency(System.currentTimeMillis() - ttsStart)
            return res
        }
        // Emergency Android TTS fallback — explicitly logged, not the default production path.
        Logger.i("VoiceModule: Sherpa TTS unavailable; using Android TTS fallback")
        return ttsPlayer.speak(text, languageCode)
    }

    /** Speaks and suspends until playback completes, preventing hands-free self-capture. */
    suspend fun speakAwait(
        text: String,
        languageCode: String = VoiceLanguage.localeTag(currentLanguage())
    ): Result<Unit> {
        if (!AgentRuntimeGate.isEnabled()) return Result.Error("UnoOne is disabled")
        VoiceService.beginAgentSpeech()
        VoiceAgentRuntime.transition(VoiceAgentState.SPEAKING, "playing local response")
        return try {
            val engine = ttsEngine
            val result = if (engine != null && engine.isInitialized()) {
                engine.speakAwait(text)
            } else {
                ttsPlayer.speakAwait(text, languageCode)
            }
            // Keep recognition gated briefly while speaker echo decays.
            delay(220L)
            result
        } finally {
            VoiceService.endAgentSpeech()
        }
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
        ttsPlayer.stop()
    }

    fun isRecording(): Boolean = isRecordingFlag.get()

    fun isSttInitialized(): Boolean = sttEngine?.isInitialized() == true

    fun isTtsInitialized(): Boolean = ttsEngine?.isInitialized() == true

    fun release() {
        stopRecording()
        sttEngine?.release()
        ttsEngine?.release()
        activeSttKey = ""
        activeTtsKey = ""
        androidStt?.release()
        ttsPlayer.release()
    }

    private fun sttKey(modelDir: String, mode: SttMode, language: String): String =
        "$modelDir|$mode|$language"
}
