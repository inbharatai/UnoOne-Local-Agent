package com.unoone.agent.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Highly compatible, multilingual STT using Android System Speech.
 * Fully supports Indian languages (Hindi, Tamil, Telugu, Malayalam, Kannada, Bengali) and English.
 */
class AndroidSttEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    fun initialize(): Result<Unit> {
        return try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return Result.Error("Speech recognition not available on this device")
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("STT init failed: ${e.message}")
        }
    }

    /**
     * Transcribe speech with support for automatic multilingual recognition, 
     * defaulting to combined English and Indian Locale.
     */
    suspend fun transcribeOnce(locale: Locale = Locale("en", "IN")): Result<String> = suspendCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
            // Enable fallback for other languages (e.g., Hindi: hi, Tamil: ta, Telugu: te)
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("en-IN", "hi-IN", "ta-IN", "te-IN", "kn-IN", "ml-IN", "bn-IN"))
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Logger.d("Multilingual STT: Ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Logger.e("Multilingual STT Error: $error")
                continuation.resume(Result.Error("Speech error code: $error"))
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Logger.i("Multilingual STT Transcribed: '$text'")
                continuation.resume(Result.Success(text))
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
