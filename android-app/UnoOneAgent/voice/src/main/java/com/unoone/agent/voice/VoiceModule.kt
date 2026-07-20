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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
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
    @Volatile private var androidStt: AndroidSttEngine? = null
    private val ttsPlayer = TtsPlayer()

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

    private val activeSttJob = AtomicReference<Deferred<Result<String>>?>(null)
    private val isRecordingFlag = AtomicBoolean(false)

    init {
        // Initialize the universal Android TTS player immediately (used as the emergency TTS path).
        ttsPlayer.initialize(context)
    }

    var onAmplitude: ((Float) -> Unit)? = null
        set(value) {
            field = value
            recorder.onAmplitude = value
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
            useAndroidStt = false
            sttState = VoiceRuntimeState.SHERPA
            Logger.i("VoiceModule: Using Sherpa-ONNX for STT (offline, $mode/$language)")
            result
        } else {
            // Do NOT silently flip to Android STT. Surface the missing-model state so the UI can
            // prompt the user to install the model (or opt into the emergency system fallback).
            sttEngine = null
            sttState = if (allowSystemSttFallback) VoiceRuntimeState.SYSTEM_FALLBACK else VoiceRuntimeState.UNAVAILABLE
            useAndroidStt = allowSystemSttFallback
            Logger.w("VoiceModule: Sherpa STT unavailable (${result.errorOrNull()}); system fallback ${if (allowSystemSttFallback) "enabled" else "disabled"}")
            result
        }
    }

    /**
     * Re-initialize STT and TTS for the active voice language (read from SharedPreferences via
     * [VoiceLanguage]), releasing the previous engines first. Used by [UnoOneApplication] at startup
     * and by Settings when the user changes the language. [modelBaseDir] is the models root
     * (typically `getExternalFilesDir(null)/models`). Does not touch the recorder or Android fallback.
     */
    @Synchronized
    fun reinitForLanguage(modelBaseDir: String, lang: String = currentLanguage()): Pair<Result<Unit>, Result<Unit>> {
        runCatching { sttEngine?.release() }
        sttEngine = null
        runCatching { ttsEngine?.release() }
        ttsEngine = null
        val asr = VoiceLanguage.asrSpec(lang)
        val sttResult = initStt("$modelBaseDir/${asr.folder}", asr.mode, asr.language)
        val ttsResult = initTts("$modelBaseDir/${VoiceLanguage.ttsFolder(lang)}")
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
            ttsState = VoiceRuntimeState.SHERPA
            Logger.i("VoiceModule: Using Sherpa-ONNX for TTS (offline)")
            result
        } else {
            // TTS keeps a graceful Android fallback so the agent can still speak without a model —
            // but this is explicitly marked as the emergency system fallback, not the default path.
            ttsEngine = null
            ttsState = VoiceRuntimeState.SYSTEM_FALLBACK
            Logger.w("VoiceModule: Sherpa TTS unavailable (${result.errorOrNull()}); using Android TTS fallback")
            result
        }
    }

    fun startRecording(context: Context, scope: CoroutineScope): Result<Unit> {
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
                    onAmplitude = onAmplitude
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
        if (!isRecordingFlag.getAndSet(false)) return Result.Error("No active voice capture session")
        VoiceAgentRuntime.transition(VoiceAgentState.PROCESSING, "transcribing final utterance")

        return if (useAndroidStt && sttEngine == null) {
            androidStt?.stopListening()
            val job = activeSttJob.getAndSet(null)
                ?: return Result.Error("No active STT job")
            val res = job.await()
            // Android STT doesn't expose confidence; assume full only on a non-empty success,
            // otherwise reset to 0 so a stale value never feeds the low-confidence retry logic.
            lastSttConfidence = if (res is Result.Success && res.data.isNotBlank()) 1f else 0f
            res
        } else {
            // Sherpa decoding is CPU-heavy and may take multiple seconds on a phone. This method is
            // often called by a Main-scoped ViewModel coroutine, so own the dispatcher boundary here
            // instead of requiring every UI/agent caller to remember to move it off the UI thread.
            withContext(Dispatchers.IO) {
                val engine = sttEngine
                    ?: return@withContext Result.Error("Offline STT model not installed. Install the Sherpa ASR model or enable the system fallback in Settings.")
                val pcm = recorder.stop()
                if (pcm.isEmpty()) return@withContext Result.Error("No audio captured")
                val sttStart = System.currentTimeMillis()
                val res = engine.transcribe(pcm)
                com.unoone.agent.observability.Diagnostics.recordSttLatency(System.currentTimeMillis() - sttStart)
                lastSttConfidence = if (res is Result.Success) engine.lastConfidence else 0f
                res
            }
        }
    }

    /**
     * Decode PCM captured by the background wake service through this application-owned Sherpa
     * engine. VoiceService no longer constructs a second full STT model.
     */
    suspend fun transcribePcm(pcmData: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty()) return@withContext Result.Error("No audio captured")
        val engine = sttEngine
            ?: return@withContext Result.Error("Offline STT model not installed")
        val result = engine.transcribe(pcmData)
        lastSttConfidence = if (result is Result.Success) engine.lastConfidence else 0f
        result
    }

    fun stopRecording(): ByteArray {
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
            engine.transcribeOnce(locale, onAmplitude)
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
        isRecordingFlag.set(false)
        activeSttJob.getAndSet(null)?.cancel()
        recorder.stop()
        sttEngine?.release()
        ttsEngine?.release()
        androidStt?.release()
        ttsPlayer.release()
    }
}
