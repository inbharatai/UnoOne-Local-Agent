package com.unoone.agent.voice.tts

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
// import com.k2fsa.sherpa.onnx.OfflineTts
// import com.k2fsa.sherpa.onnx.OfflineTtsConfig
// import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
// import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

class SherpaTtsEngine(private val modelDir: String) {

    // private var tts: OfflineTts? = null
    private val ttsPlayer = TtsPlayer()
    private var initialized = false

    fun initialize(): Result<Unit> {
        return try {
            // val config = OfflineTtsConfig(
            //     modelConfig = OfflineTtsModelConfig(
            //         vits = OfflineTtsVitsModelConfig(
            //             model = "$modelDir/model.onnx",
            //             tokens = "$modelDir/tokens.txt",
            //             dataDir = "$modelDir/espeak-ng-data"
            //         ),
            //         numThreads = 2,
            //         debug = false
            //     )
            // )
            // tts = OfflineTts(config)
            initialized = true
            Logger.i("SherpaTtsEngine initialized successfully (STUBBED)")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("SherpaTtsEngine init failed: ${e.message}", e)
            Result.Error("TTS init failed: ${e.message}")
        }
    }

    fun speak(text: String): Result<Unit> {
        if (!initialized) return Result.Error("TTS engine not initialized")
        // val engine = tts ?: return Result.Error("TTS engine is null")

        return try {
            // val audio = engine.generate(text, sid = 0, speed = 1.0f)
            // ttsPlayer.playPcm(audio.samples, sampleRate = audio.sampleRate)
            Logger.i("TTS spoke: '$text' (STUBBED)")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("TTS speak failed", e)
            Result.Error("TTS failed: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = initialized

    fun stop() {
        ttsPlayer.stop()
    }

    fun release() {
        stop()
        // try {
        //     tts?.close()
        // } catch (e: Exception) {
        //     Logger.e("Error releasing TTS engine", e)
        // }
        // tts = null
        initialized = false
        Logger.i("TTS engine released")
    }
}
