package com.unoone.agent.voice.stt

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * World-class offline Keyword Spotter using Sherpa-ONNX.
 * Uses reflection/safe-loading to ensure the app compiles and runs perfectly 
 * on any Android device even if the native .so libraries are missing.
 */
class KeywordSpotterEngine(private val modelDir: String, private val cacheDir: String? = null) {

    private var spotter: Any? = null
    @Volatile private var initialized = false
    private var keywordFilePath: String? = null

    fun initialize(keywords: List<String>): Result<Unit> {
        return try {
            Logger.i("KeywordSpotterEngine: Checking model files in $modelDir")
            val encoderFile = java.io.File("$modelDir/encoder.onnx")
            val decoderFile = java.io.File("$modelDir/decoder.onnx")
            val joinerFile = java.io.File("$modelDir/joiner.onnx")
            val tokensFile = java.io.File("$modelDir/tokens.txt")

            if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() || !tokensFile.exists()) {
                return Result.Error("Sherpa KWS model files missing. Please download models to: $modelDir")
            }

            // Create temporary keyword file
            val keywordFile = createKeywordFile(keywords)

            // Attempt to load Sherpa-ONNX classes dynamically
            val configClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterConfig")
            val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            val transducerConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig")
            val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")

            // Build configs using reflection
            val transducerConfig = transducerConfigClass.getConstructor(
                String::class.java, String::class.java, String::class.java
            ).newInstance(encoderFile.absolutePath, decoderFile.absolutePath, joinerFile.absolutePath)

            val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance()
            modelConfigClass.getMethod("setTransducer", transducerConfigClass).invoke(modelConfig, transducerConfig)
            modelConfigClass.getMethod("setTokens", String::class.java).invoke(modelConfig, tokensFile.absolutePath)
            modelConfigClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(modelConfig, 2)

            val config = configClass.getDeclaredConstructor().newInstance()
            configClass.getMethod("setModelConfig", modelConfigClass).invoke(config, modelConfig)
            configClass.getMethod("setKeywordFile", String::class.java).invoke(config, keywordFile)

            spotter = spotterClass.getConstructor(configClass).newInstance(config)
            initialized = true
            Logger.i("KeywordSpotterEngine: Offline KWS successfully initialized for: $keywords")
            Result.Success(Unit)
        } catch (e: ClassNotFoundException) {
            Logger.w("KeywordSpotterEngine: Sherpa-ONNX classes not found in classpath. Fallback mode.")
            Result.Error("Sherpa library not available")
        } catch (e: Exception) {
            Logger.e("KeywordSpotterEngine: Initialization failed", e)
            Result.Error("KWS init failed: ${e.message}")
        }
    }

    fun processChunk(pcmBytes: ByteArray): String? {
        if (!initialized || spotter == null) return null

        return try {
            val samples = FloatArray(pcmBytes.size / 2)
            val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = buffer.short.toFloat() / 32768f
            }

            val waveClass = Class.forName("com.k2fsa.sherpa.onnx.Wave")
            val wave = waveClass.getConstructor(FloatArray::class.java, Float::class.javaPrimitiveType)
                .newInstance(samples, 16000f)

            val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            val decodeMethod = spotterClass.getMethod("decode", waveClass)
            val resultClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotterResult")

            val resultObj = decodeMethod.invoke(spotter, wave)
            val keyword = resultClass.getMethod("getKeyword").invoke(resultObj) as String

            if (keyword.isNotBlank()) {
                Logger.i("KeywordSpotterEngine: Wake word detected: $keyword")
                keyword.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e("KeywordSpotterEngine: Error processing chunk", e)
            null
        }
    }

    private fun createKeywordFile(keywords: List<String>): String {
        // 0C-4: Use cacheDir (provided by caller, usually context.cacheDir) instead of
        // createTempFile + deleteOnExit. deleteOnExit is unreliable on Android (only runs
        // on clean JVM shutdown, which almost never happens). We'll delete in release() instead.
        val dir = if (cacheDir != null) java.io.File(cacheDir) else java.io.File(System.getProperty("java.io.tmpdir"))
        dir.mkdirs()
        val file = java.io.File(dir, "unoone_keywords.txt")
        file.writeText(keywords.joinToString("\n") { it })
        keywordFilePath = file.absolutePath
        return file.absolutePath
    }

    fun release() {
        try {
            if (spotter != null) {
                val spotterClass = Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
                spotterClass.getMethod("close").invoke(spotter)
            }
        } catch (e: Exception) {
            Logger.e("KeywordSpotterEngine: Error closing spotter", e)
        }
        spotter = null
        initialized = false

        // 0C-4: Clean up keyword temp file
        keywordFilePath?.let {
            try {
                java.io.File(it).delete()
            } catch (_: Exception) { }
        }
        keywordFilePath = null
    }
}