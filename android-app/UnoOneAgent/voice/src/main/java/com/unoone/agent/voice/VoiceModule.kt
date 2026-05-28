package com.unoone.agent.voice

import android.content.Context
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.tts.SherpaTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceModule(private val context: Context) {

    private val recorder = AudioRecorder()
    private var sttEngine: SherpaSttEngine? = null
    private var ttsEngine: SherpaTtsEngine? = null
    private var androidStt: AndroidSttEngine? = null
    private var useAndroidStt = false

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
            // Android fallback uses its own recording, so we just return the transcript
            // The caller should use transcribeWithAndroid() instead
            Result.Error("Use Android STT for transcription")
        } else {
            sttEngine!!.transcribe(pcm)
        }
    }

    fun stopRecording(): ByteArray {
        return recorder.stop()
    }

    suspend fun transcribeWithAndroid(): Result<String> {
        return withContext(Dispatchers.Main) {
            val engine = androidStt ?: AndroidSttEngine(context).also { androidStt = it }
            val initResult = engine.initialize()
            if (initResult is Result.Error) return@withContext initResult
            engine.transcribeOnce()
        }
    }

    fun speak(text: String): Result<Unit> {
        val engine = ttsEngine
        if (engine != null && engine.isInitialized()) {
            return engine.speak(text)
        }
        // Android TTS fallback handled by the caller (AndroidSttEngine is STT only)
        Logger.w("No TTS engine available for speaking: $text")
        return Result.Error("No TTS engine available")
    }

    fun stopSpeaking() {
        ttsEngine?.stop()
    }

    fun isRecording(): Boolean = recorder.isRecording()

    fun isSttInitialized(): Boolean = sttEngine?.isInitialized() == true

    fun isTtsInitialized(): Boolean = ttsEngine?.isInitialized() == true

    fun release() {
        recorder.stop()
        sttEngine?.release()
        ttsEngine?.release()
        androidStt?.release()
    }
}