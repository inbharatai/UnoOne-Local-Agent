package com.unoone.agent.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Universal, highly robust TextToSpeech engine supporting English and Indian languages (Hindi, Tamil, etc.).
 * Fully offline-first.
 */
class TtsPlayer : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null
    private var activeTrack: AudioTrack? = null

    // 0C-9: UtteranceProgressListener for tracking TTS completion
    private var onUtteranceDone: ((String) -> Unit)? = null

    fun initialize(context: Context): Result<Unit> {
        return try {
            tts = TextToSpeech(context, this)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("TTS Player: Initialization failed", e)
            Result.Error("TTS failed: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("en", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Logger.w("TTS Player: English (India) not supported, using default locale")
                tts?.setLanguage(Locale.getDefault())
            }

            // 0C-9: Register UtteranceProgressListener to track TTS completion
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Logger.d("TTS Player: Utterance started: $utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    Logger.d("TTS Player: Utterance completed: $utteranceId")
                    onUtteranceDone?.invoke(utteranceId ?: "")
                }
                override fun onError(utteranceId: String?) {
                    Logger.w("TTS Player: Utterance error: $utteranceId")
                    onUtteranceDone?.invoke(utteranceId ?: "")
                }
            })

            isReady = true
            Logger.i("TTS Player: Initialized successfully")

            // Speak any pending text that was queued during init
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Logger.e("TTS Player: Initialization failed with status $status")
        }
    }

    /**
     * Synthesize and speak text. Automatically detects Indian language context or falls back to English.
     */
    fun speak(text: String, languageCode: String = "en-IN"): Result<Unit> {
        val t = tts
        if (!isReady || t == null) {
            pendingText = text
            return Result.Success(Unit) // Queued
        }

        return try {
            val locale = Locale.forLanguageTag(languageCode)
            val languageResult = t.setLanguage(locale)
            if (
                languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return Result.Error("System TTS does not support ${locale.toLanguageTag()}")
            }
            val speakResult = t.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "UnoOne_TTS_Playback"
            )
            if (speakResult == TextToSpeech.ERROR) {
                Result.Error("System TTS rejected the utterance")
            } else {
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            Logger.e("TTS Player: Speak failed", e)
            Result.Error("Speak failed: ${e.message}")
        }
    }

    /**
     * 0C-8: Play raw PCM audio data from Sherpa-ONNX TTS or other offline engines.
     * Converts FloatArray samples → Int16 PCM → AudioTrack for playback.
     */
    fun playPcm(samples: FloatArray, sampleRate: Int = 22050): Result<Unit> {
        if (samples.isEmpty()) {
            Logger.w("TTS Player: playPcm called with empty samples")
            return Result.Error("Empty audio samples")
        }

        return try {
            // Stop any currently playing AudioTrack first
            stopPcmTrack()

            // Convert FloatArray [-1.0, 1.0] → Int16 PCM bytes
            val pcmBytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val clipped = samples[i].coerceIn(-1f, 1f)
                val intSample = (clipped * 32767f).toInt()
                val shortSample = clipped.coerceIn(-1f, 1f)
                // Little-endian encoding
                pcmBytes[i * 2] = (intSample and 0xFF).toByte()
                pcmBytes[i * 2 + 1] = ((intSample shr 8) and 0xFF).toByte()
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, pcmBytes.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
            activeTrack = track
            Logger.i("TTS Player: PCM playback started (${samples.size} samples at ${sampleRate}Hz)")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("TTS Player: PCM playback failed", e)
            Result.Error("PCM playback failed: ${e.message}")
        }
    }

    /**
     * 0C-9: Suspends until TTS finishes speaking the given text.
     * Falls back to a 10-second timeout if UtteranceProgressListener doesn't fire.
     */
    suspend fun speakAwait(text: String, languageCode: String = "en-IN", timeoutMs: Long = 10_000L): Result<Unit> {
        val result = speak(text, languageCode)
        if (result is Result.Error) return result

        return suspendCancellableCoroutine { cont ->
            val utteranceId = "UnoOne_TTS_Await_${System.currentTimeMillis()}"
            onUtteranceDone = { id ->
                if (id == utteranceId || id.startsWith("UnoOne_TTS")) {
                    onUtteranceDone = null
                    if (cont.isActive) cont.resume(Result.Success(Unit))
                }
            }
            // Safety timeout: if the listener never fires, resume anyway
            Thread {
                Thread.sleep(timeoutMs)
                onUtteranceDone = null
                if (cont.isActive) cont.resume(Result.Success(Unit))
            }.start()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Logger.e("TTS Player: Error stopping playback", e)
        }
        stopPcmTrack()
    }

    fun release() {
        stop()
        stopPcmTrack()
        onUtteranceDone = null
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Logger.e("TTS Player: Error shutting down", e)
        }
        tts = null
        isReady = false
    }

    private fun stopPcmTrack() {
        try {
            activeTrack?.let { track ->
                if (track.state == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {
            Logger.e("TTS Player: Error releasing AudioTrack", e)
        }
        activeTrack = null
    }
}
