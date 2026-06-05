package com.unoone.agent.voice.tts

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger

/**
 * World-class offline TTS using Sherpa-ONNX.
 * Uses reflection/safe-loading to ensure the app compiles and runs perfectly 
 * on any Android device even if the native .so libraries are missing.
 */
class SherpaTtsEngine(private val modelDir: String) {

    @Volatile private var tts: Any? = null
    private val ttsPlayer = TtsPlayer()
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(): Result<Unit> {
        return try {
            Logger.i("SherpaTtsEngine: Checking model files in $modelDir")
            val modelFile = java.io.File("$modelDir/model.onnx")
            val tokensFile = java.io.File("$modelDir/tokens.txt")
            val espeakDataDir = java.io.File("$modelDir/espeak-ng-data")

            if (!modelFile.exists() || !tokensFile.exists() || !espeakDataDir.exists()) {
                return Result.Error("Sherpa TTS model files missing. Please download models to: $modelDir")
            }

            // Attempt to load Sherpa-ONNX classes dynamically
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
            val vitsConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
            val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")

            // Build configs using reflection
            val vitsConfig = vitsConfigClass.getConstructor(
                String::class.java, String::class.java, String::class.java
            ).newInstance(modelFile.absolutePath, tokensFile.absolutePath, espeakDataDir.absolutePath)

            val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance()
            modelConfigClass.getMethod("setVits", vitsConfigClass).invoke(modelConfig, vitsConfig)
            modelConfigClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(modelConfig, 2)

            val config = configClass.getDeclaredConstructor().newInstance()
            configClass.getMethod("setModelConfig", modelConfigClass).invoke(config, modelConfig)

            tts = ttsClass.getConstructor(configClass).newInstance(config)
            initialized = true
            Logger.i("SherpaTtsEngine: Offline TTS successfully initialized with high-quality models")
            Result.Success(Unit)
        } catch (e: ClassNotFoundException) {
            Logger.w("SherpaTtsEngine: Sherpa-ONNX classes not found in classpath. Falling back to System TTS.")
            Result.Error("Sherpa library not available")
        } catch (e: Exception) {
            Logger.e("SherpaTtsEngine: Initialization failed", e)
            Result.Error("Sherpa TTS failed: ${e.message}")
        }
    }

    @Synchronized
    fun speak(text: String): Result<Unit> {
        if (!initialized || tts == null) {
            return Result.Error("SherpaTtsEngine not initialized")
        }

        return try {
            val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            val generateMethod = ttsClass.getMethod("generate", String::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val audioClass = Class.forName("com.k2fsa.sherpa.onnx.GeneratedAudio")

            val audioObj = generateMethod.invoke(tts, text, 0, 1.0f)
            val samples = audioClass.getMethod("getSamples").invoke(audioObj) as FloatArray
            val sampleRate = audioClass.getMethod("getSampleRate").invoke(audioObj) as Float

            ttsPlayer.playPcm(samples, sampleRate.toInt())
            Logger.i("SherpaTtsEngine: Speech generated successfully")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("SherpaTtsEngine: Speech generation failed", e)
            Result.Error("TTS synthesis failed: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = initialized

    fun stop() {
        ttsPlayer.stop()
    }

    @Synchronized
    fun release() {
        stop()
        try {
            if (tts != null) {
                val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
                ttsClass.getMethod("close").invoke(tts)
            }
        } catch (e: Exception) {
            Logger.e("SherpaTtsEngine: Error closing TTS", e)
        }
        tts = null
        initialized = false
    }
}