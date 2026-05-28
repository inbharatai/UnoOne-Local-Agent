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
 * Highly compatible STT using Android System Speech.
 * Works on every Android device without extra model downloads.
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

    suspend fun transcribeOnce(): Result<String> = suspendCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Logger.d("STT Ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Logger.e("STT Error: $error")
                continuation.resume(Result.Error("Speech error code: $error"))
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
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
