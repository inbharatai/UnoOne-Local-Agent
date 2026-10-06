package com.unoone.agent.voice.stt

import com.unoone.agent.voice.processMicrophoneLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional Android on-device speech fallback (API 31+ with an installed local recognizer).
 * Language availability is device-dependent. Never substitutes a provider-dependent/cloud recognizer.
 */
class AndroidSttEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var destructionAck = true
    fun isDrainAcknowledged(): Boolean = destructionAck

    fun initialize(): Result<Unit> {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                return Result.Error("Local system speech recognition unavailable. Install UnoOne's offline speech models; no cloud fallback will be used.")
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error("STT init failed: ${e.message}")
        }
    }

    /**
     * Transcribe speech with support for automatic multilingual recognition,
     * defaulting to combined English and Indian Locale.
     * Includes a 15-second timeout to prevent indefinite hangs.
     */
    suspend fun transcribeOnce(
        locale: Locale = Locale("en", "IN"),
        onAmplitude: ((Float) -> Unit)? = null
    ): Result<String> {
        // Reject unsupported/local-recognizer-unavailable requests before taking the mic
        // or posting work to Main. Never create a provider-dependent recognizer.
        if (android.os.Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            return Result.Error("Local system recognizer unavailable; offline models required")
        val owner = Any()
        if (!processMicrophoneLease.acquire(owner)) return Result.Error("Microphone busy")
        try {
            // withContext awaits the recognition child, including cancellation, before returning.
            return withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(15_000L) {
                    suspendCancellableCoroutine<Result<String>> { continuation ->
                        doTranscribe(locale, onAmplitude, continuation)
                    }
                } ?: Result.Error("Speech recognition timed out")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                speechRecognizer?.let { safeDestroyRecognizer(it) }
            }
            processMicrophoneLease.release(owner, destructionAck, true)
        }
    }

    private fun doTranscribe(
        locale: Locale,
        onAmplitude: ((Float) -> Unit)?,
        continuation: CancellableContinuation<Result<String>>
    ) {
        if (android.os.Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            if (continuation.isActive) continuation.resume(Result.Error("Local system recognizer unavailable; offline models required"))
            return
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            speechRecognizer = it
            destructionAck = false
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toString())
            // Enable fallback for other languages (e.g., Hindi: hi, Tamil: ta, Telugu: te)
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("en-IN", "hi-IN"))
        }

        val resumed = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        fun allowed(): Boolean {
            val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
            return mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION &&
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        fun complete(result: Result<String>) {
            if (resumed.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(null)
                safeDestroyRecognizer(recognizer)
                if (continuation.isActive) continuation.resume(result)
            }
        }
        val monitor = object : Runnable {
            override fun run() {
                if (!allowed()) complete(Result.Error("Speech capture discarded during call or permission loss"))
                else if (!resumed.get()) handler.postDelayed(this, 50L)
            }
        }
        continuation.invokeOnCancellation {
            resumed.set(true)
            handler.removeCallbacksAndMessages(null)
            handler.post { safeDestroyRecognizer(recognizer) }
        }
        if (!allowed()) { complete(Result.Error("Speech capture unavailable")); return }
        handler.post(monitor)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Logger.d("Multilingual STT: Ready")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Normalize rmsdB (typically ranges from -2 to 10+) to 0..1 range
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                if (allowed() && !resumed.get()) onAmplitude?.invoke(normalized)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Logger.e("Multilingual STT Error: $error")
                complete(Result.Error("Speech error code: $error"))
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                val accepted = allowed()
                Logger.i("Multilingual STT: result received, accepted=$accepted")
                complete(if (accepted) Result.Success(text) else Result.Error("Speech capture discarded during call"))
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try { recognizer.startListening(intent) } catch (e: Exception) { complete(Result.Error("Speech capture failed", e)) }
    }

    /**
     * Safely destroys the recognizer exactly once, preventing double-destroy
     * if both onError and onResults fire in rapid succession.
     */
    private fun safeDestroyRecognizer(recognizer: SpeechRecognizer) {
        synchronized(this) {
            if (speechRecognizer == recognizer) {
                try {
                    recognizer.destroy()
                    speechRecognizer = null
                    destructionAck = true
                } catch (e: Exception) {
                    Logger.e("AndroidSttEngine: Error destroying recognizer", e)
                }
            }
        }
    }

    fun stopListening() {
        Handler(Looper.getMainLooper()).post {
            try { speechRecognizer?.stopListening() }
            catch (e: Exception) { Logger.e("AndroidSttEngine: Error stopping listening", e) }
        }
    }

    /** Called on Main after the recognition child has joined. */
    fun release() { speechRecognizer?.let { safeDestroyRecognizer(it) } }
}
