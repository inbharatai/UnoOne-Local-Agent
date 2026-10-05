package com.unoone.agent.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Universal, highly robust TextToSpeech engine supporting English and Indian languages (Hindi, Tamil, etc.).
 * Fully offline-first.
 */
class TtsPlayer(context: Context? = null) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: Pair<String, String>? = null
    private var activeTrack: AudioTrack? = null
    private var audioManager: AudioManager? = context?.getSystemService(AudioManager::class.java)
    private var audioFocusRequest: AudioFocusRequest? = null
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // 0C-9: UtteranceProgressListener for tracking TTS completion
    @Volatile private var onUtteranceDone: ((String, Result<Unit>) -> Unit)? = null
    private val awaitMutex = Mutex()

    fun initialize(context: Context): Result<Unit> {
        return try {
            audioManager = context.getSystemService(AudioManager::class.java)
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
                    onUtteranceDone?.invoke(utteranceId ?: "", Result.Success(Unit))
                }
                override fun onError(utteranceId: String?) {
                    Logger.w("TTS Player: Utterance error: $utteranceId")
                    onUtteranceDone?.invoke(utteranceId ?: "", Result.Error("System TTS playback failed"))
                }
            })

            isReady = true
            Logger.i("TTS Player: Initialized successfully")

            // Speak any pending text that was queued during init
            pendingText?.let {
                speak(it.first, it.second)
                pendingText = null
            }
        } else {
            Logger.e("TTS Player: Initialization failed with status $status")
        }
    }

    /**
     * Synthesize and speak text. Automatically detects Indian language context or falls back to English.
     */
    fun speak(text: String, languageCode: String = "en-IN", utteranceId: String = "UnoOne_TTS_Playback"): Result<Unit> {
        val t = tts
        if (!isReady || t == null) {
            pendingText = text to languageCode
            return Result.Success(Unit) // Queued
        }

        return try {
            val locale = Locale.forLanguageTag(languageCode)
            val voice = t.voices?.filter {
                !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }?.sortedByDescending { it.locale.country == locale.country }?.firstOrNull()
                ?: return Result.Error("No installed offline voice for ${locale.toLanguageTag()}")
            if (t.setVoice(voice) == TextToSpeech.ERROR) return Result.Error("Offline voice selection failed")
            val languageResult = t.isLanguageAvailable(locale)
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
                utteranceId
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
    @Synchronized
    fun playPcm(samples: FloatArray, sampleRate: Int = 22050): Result<Unit> {
        if (samples.isEmpty()) {
            Logger.w("TTS Player: playPcm called with empty samples")
            return Result.Error("Empty audio samples")
        }

        return try {
            // Stop any currently playing AudioTrack first
            stopPcmTrack()

            val rms = sqrt(samples.fold(0.0) { sum, sample -> sum + sample * sample } / samples.size)
            val peak = samples.maxOf { kotlin.math.abs(it) }
            if (peak < 0.001f || rms < 0.0001) {
                Logger.e("TTS Player: synthesized PCM is effectively silent (peak=$peak rms=$rms)")
                return Result.Error("Synthesized speech was silent")
            }

            val manager = audioManager
            if (manager != null) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(speechAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioFocusRequest = focusRequest
                if (manager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Logger.w("TTS Player: audio focus was not granted; attempting direct playback")
                }
            }

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
                    speechAttributes
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

            activeTrack = track
            check(track.write(pcmBytes, 0, pcmBytes.size) == pcmBytes.size) { "Incomplete PCM write" }
            track.setVolume(1.0f)
            track.play()
            activeTrack = track
            Logger.i("TTS Player: audible PCM playback started (${samples.size} samples at ${sampleRate}Hz, peak=${"%.3f".format(peak)}, rms=${"%.3f".format(rms)})")
            Result.Success(Unit)
        } catch (e: Exception) {
            stopPcmTrack()
            Logger.e("TTS Player: PCM playback failed", e)
            Result.Error("PCM playback failed: ${e.message}")
        }
    }

    /**
     * 0C-9: Suspends until TTS finishes speaking the given text.
     * Falls back to a 10-second timeout if UtteranceProgressListener doesn't fire.
     */
    suspend fun speakAwait(text: String, languageCode: String = "en-IN", timeoutMs: Long = 10_000L): Result<Unit> = awaitMutex.withLock {
        if (!isReady || tts == null) return@withLock Result.Error("System TTS is not ready")
        val completion = CompletableDeferred<Result<Unit>>()
        val id = "UnoOne_TTS_${java.util.UUID.randomUUID()}"
        onUtteranceDone = { reportedId, result ->
            if (reportedId == id || reportedId == "") completion.complete(result)
        }
        try {
            val started = speak(text, languageCode, id)
            if (started is Result.Error) started
            else withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { completion.await() }
                ?: Result.Error("System TTS playback timed out")
        } finally {
            onUtteranceDone = null
            pendingText = null
            runCatching { tts?.stop() }
        }
    }

    fun stop() {
        onUtteranceDone?.invoke("", Result.Error("Speech interrupted"))
        pendingText = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Logger.e("TTS Player: Error stopping playback", e)
        }
        stopPcmTrack()
    }

    /** Release the static AudioTrack and transient focus after a suspending caller heard it. */
    fun finishPcmPlayback() {
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

    @Synchronized
    private fun stopPcmTrack() {
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            } catch (e: Exception) {
                Logger.e("TTS Player: Error stopping AudioTrack", e)
            } finally {
                runCatching { track.release() }
            }
        }
        audioFocusRequest?.let { request ->
            runCatching { audioManager?.abandonAudioFocusRequest(request) }
        }
        audioFocusRequest = null
    }
}
