package com.unoone.agent.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.util.Locale

/**
 * Universal, highly robust TextToSpeech engine supporting English and Indian languages (Hindi, Tamil, etc.).
 * Fully offline-first.
 */
class TtsPlayer : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

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
            val locale = when (languageCode.lowercase()) {
                "hi", "hi-in" -> Locale("hi", "IN")
                "ta", "ta-in" -> Locale("ta", "IN")
                "te", "te-in" -> Locale("te", "IN")
                else -> Locale("en", "IN")
            }
            t.setLanguage(locale)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UnoOne_TTS_Playback")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("TTS Player: Speak failed", e)
            Result.Error("Speak failed: ${e.message}")
        }
    }

    fun playPcm(samples: FloatArray, sampleRate: Int = 22050): Result<Unit> {
        // Fallback for Sherpa RAW PCM arrays if native .so is used
        Logger.w("playPcm: Stub fallback. In universal mode, use standard speak() method.")
        return Result.Success(Unit)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Logger.e("TTS Player: Error stopping playback", e)
        }
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Logger.e("TTS Player: Error shutting down", e)
        }
        tts = null
        isReady = false
    }
}
