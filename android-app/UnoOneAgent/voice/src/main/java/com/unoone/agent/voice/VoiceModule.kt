package com.unoone.agent.voice

import android.content.Context
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.tts.SherpaTtsEngine
import com.unoone.agent.voice.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoiceModule(private val context: Context) {

    private val recorder = AudioRecorder()
    @Volatile private var sttEngine: SherpaSttEngine? = null
    @Volatile private var ttsEngine: SherpaTtsEngine? = null
    @Volatile private var androidStt: AndroidSttEngine? = null
    private val ttsPlayer = TtsPlayer()
    @Volatile private var useAndroidStt = true

    private val activeSttJob = AtomicReference<Deferred<Result<String>>?>(null)
    private val isRecordingFlag = AtomicBoolean(false)

    init {
        // Initialize the universal, high-quality native TTS player immediately
        ttsPlayer.initialize(context)
    }

    var onAmplitude: ((Float) -> Unit)? = null
        set(value) {
            field = value
            recorder.onAmplitude = value
        }

    fun initStt(modelDir: String): Result<Unit> {
        val engine = SherpaSttEngine(modelDir)
        val result = engine.initialize()
        return if (result is Result.Success) {
            sttEngine = engine
            useAndroidStt = false
            Logger.i("Using Sherpa-ONNX for STT")
            result
        } else {
            Logger.w("Sherpa STT init failed, falling back to Android SpeechRecognizer")
            useAndroidStt = true
            Result.Success(Unit)
        }
    }

    fun initTts(modelDir: String): Result<Unit> {
        val engine = SherpaTtsEngine(modelDir)
        val result = engine.initialize()
        return if (result is Result.Success) {
            ttsEngine = engine
            Logger.i("Using Sherpa-ONNX for TTS")
            result
        } else {
            Logger.w("Sherpa TTS init failed, TTS will use Android fallback")
            result
        }
    }

    fun startRecording(context: Context, scope: CoroutineScope): Result<Unit> {
        if (!isRecordingFlag.compareAndSet(false, true)) return Result.Success(Unit)

        return if (useAndroidStt || sttEngine == null) {
            val engine = androidStt ?: AndroidSttEngine(context).also { androidStt = it }
            val initResult = engine.initialize()
            if (initResult is Result.Error) {
                isRecordingFlag.set(false)
                return initResult
            }

            activeSttJob.set(scope.async(Dispatchers.Main) {
                engine.transcribeOnce(onAmplitude = onAmplitude)
            })
            Result.Success(Unit)
        } else {
            if (!recorder.hasPermission(context)) {
                isRecordingFlag.set(false)
                return Result.Error("Microphone permission not granted")
            }
            val result = recorder.start()
            if (result is Result.Error) {
                isRecordingFlag.set(false)
            }
            result
        }
    }

    suspend fun stopAndTranscribe(): Result<String> {
        if (!isRecordingFlag.getAndSet(false)) return Result.Error("No active voice capture session")

        return if (useAndroidStt || sttEngine == null) {
            androidStt?.stopListening()
            val job = activeSttJob.getAndSet(null)
                ?: return Result.Error("No active STT job")
            val res = job.await()
            res
        } else {
            val pcm = recorder.stop()
            if (pcm.isEmpty()) return Result.Error("No audio captured")
            sttEngine!!.transcribe(pcm)
        }
    }

    fun stopRecording(): ByteArray {
        isRecordingFlag.set(false)
        // Cancel the active STT job to prevent orphaned coroutines
        activeSttJob.getAndSet(null)?.cancel()
        return recorder.stop()
    }

    /**
     * Highly accurate, multilingual on-device transcription supporting English and Indian languages.
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
     * Highly accurate, offline speech synthesis supporting Indian languages (Hindi, Tamil, Telugu) and English.
     */
    fun speak(text: String, languageCode: String = "en-IN"): Result<Unit> {
        val engine = ttsEngine
        if (engine != null && engine.isInitialized()) {
            return engine.speak(text)
        }
        // Universal Android Fallback
        Logger.i("VoiceModule: Synthesizing speech via native TTS: '$text'")
        return ttsPlayer.speak(text, languageCode)
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