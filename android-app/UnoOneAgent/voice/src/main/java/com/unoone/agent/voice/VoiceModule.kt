package com.unoone.agent.voice

import android.content.Context
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.tts.SherpaTtsEngine
import com.unoone.agent.voice.tts.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceModule(private val context: Context) {

    private val recorder = AudioRecorder()
    private var sttEngine: SherpaSttEngine? = null
    private var ttsEngine: SherpaTtsEngine? = null
    private var androidStt: AndroidSttEngine? = null
    private val ttsPlayer = TtsPlayer()
    private var useAndroidStt = true

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

    fun startRecording(context: Context): Result<Unit> {
        if (!recorder.hasPermission(context)) {
            return Result.Error("Microphone permission not granted")
        }
        return recorder.start()
    }

    suspend fun stopAndTranscribe(): Result<String> {
        val pcm = recorder.stop()
        if (pcm.isEmpty()) return Result.Error("No audio captured")

        return if (useAndroidStt || sttEngine == null) {
            transcribeWithAndroid()
        } else {
            sttEngine!!.transcribe(pcm)
        }
    }

    fun stopRecording(): ByteArray {
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
            engine.transcribeOnce(locale)
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

    fun isRecording(): Boolean = recorder.isRecording()

    fun isSttInitialized(): Boolean = sttEngine?.isInitialized() == true

    fun isTtsInitialized(): Boolean = ttsEngine?.isInitialized() == true

    fun release() {
        recorder.stop()
        sttEngine?.release()
        ttsEngine?.release()
        androidStt?.release()
        ttsPlayer.release()
    }
}