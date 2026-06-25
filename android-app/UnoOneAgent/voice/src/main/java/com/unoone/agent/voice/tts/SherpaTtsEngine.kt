package com.unoone.agent.voice.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.io.File

/**
 * Production offline TTS using Sherpa-ONNX (VITS models: model.onnx + tokens.txt + espeak-ng-data).
 *
 * Real direct-API implementation (no reflection). Generates PCM via Sherpa and plays it through
 * the shared [TtsPlayer] (AudioTrack). Degrades gracefully if the native `.so` fails to load.
 */
class SherpaTtsEngine(private val context: Context, private val modelDir: String) {

    private val player = TtsPlayer()
    @Volatile
    private var tts: OfflineTts? = null
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(): Result<Unit> {
        return try {
            Logger.i("SherpaTtsEngine: Checking model files in $modelDir")
            val model = File("$modelDir/model.onnx")
            val tokens = File("$modelDir/tokens.txt")
            val espeakData = File("$modelDir/espeak-ng-data")

            if (!model.exists() || !tokens.exists() || !espeakData.exists()) {
                return Result.Error("Sherpa TTS model files missing. Please download models to: $modelDir")
            }

            val vits = OfflineTtsVitsModelConfig().apply {
                this.model = model.absolutePath
                this.tokens = tokens.absolutePath
                dataDir = espeakData.absolutePath
            }
            val modelConfig = OfflineTtsModelConfig().apply {
                this.vits = vits
                numThreads = 2
            }
            val config = OfflineTtsConfig().apply {
                this.model = modelConfig
            }

            tts = OfflineTts(context.assets, config)
            initialized = true
            Logger.i("SherpaTtsEngine: Offline TTS initialized (VITS, 2 threads)")
            Result.Success(Unit)
        } catch (e: Throwable) {
            Logger.e("SherpaTtsEngine: Initialization failed: ${e::class.java.simpleName}: ${e.message}")
            tts = null
            initialized = false
            Result.Error("Sherpa TTS unavailable: ${e.message}")
        }
    }

    @Synchronized
    fun speak(text: String): Result<Unit> {
        val engine = tts
        if (!initialized || engine == null) {
            return Result.Error("SherpaTtsEngine not initialized")
        }
        if (text.isBlank()) return Result.Success(Unit)

        return try {
            // generate(text, speakerId=0, speed=1.0f)
            val audio = engine.generate(text, 0, 1.0f)
            if (audio.samples.isEmpty()) {
                return Result.Error("TTS produced no audio")
            }
            player.playPcm(audio.samples, audio.sampleRate)
            Logger.i("SherpaTtsEngine: Generated ${audio.samples.size} samples @ ${audio.sampleRate}Hz")
            Result.Success(Unit)
        } catch (e: Throwable) {
            Logger.e("SherpaTtsEngine: Speech generation failed: ${e::class.java.simpleName}: ${e.message}")
            Result.Error("TTS synthesis failed: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = initialized

    fun stop() {
        player.stop()
    }

    @Synchronized
    fun release() {
        stop()
        try {
            tts?.release()
        } catch (e: Throwable) {
            Logger.e("SherpaTtsEngine: Error releasing TTS", e)
        }
        player.release()
        tts = null
        initialized = false
    }
}