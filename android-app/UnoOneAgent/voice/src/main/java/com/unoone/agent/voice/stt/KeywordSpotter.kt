package com.unoone.agent.voice.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production offline keyword spotter (wake-word) using Sherpa-ONNX online transducer models
 * (encoder/decoder/joiner + tokens), driven from the continuous microphone loop in VoiceService.
 *
 * Real direct-API implementation (no reflection). Holds one [OnlineStream] and feeds 1-second PCM
 * chunks; on a hit the stream is recreated to reset decoder state. Degrades gracefully if the
 * native `.so` fails to load.
 */
class KeywordSpotterEngine(
    private val context: Context,
    private val modelDir: String,
    private val cacheDir: String? = null
) {

    private var spotter: KeywordSpotter? = null
    @Volatile
    private var initialized = false
    private var stream: OnlineStream? = null
    private var keywordFilePath: String? = null

    fun initialize(keywordEntries: List<String>): Result<Unit> {
        return try {
            Logger.i("KeywordSpotterEngine: Checking model files in $modelDir")
            val encoder = File("$modelDir/encoder.onnx")
            val decoder = File("$modelDir/decoder.onnx")
            val joiner = File("$modelDir/joiner.onnx")
            val tokens = File("$modelDir/tokens.txt")

            if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                return Result.Error("Sherpa KWS model files missing. Please download models to: $modelDir")
            }

            // Sherpa's native constructor aborts the entire process (not a catchable exception)
            // when a keyword file contains a token absent from tokens.txt. Validate on the Kotlin
            // side first so a bad phrase can only disable KWS, never crash-loop UnoOne.
            val missingTokens = missingKeywordTokens(keywordEntries, tokens.readLines())
            if (missingTokens.isNotEmpty()) {
                return Result.Error("Sherpa KWS keyword tokens missing from model: ${missingTokens.joinToString()}")
            }

            val keywordFile = createKeywordFile(keywordEntries)

            val modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder.absolutePath,
                    decoder.absolutePath,
                    joiner.absolutePath
                )
                this.tokens = tokens.absolutePath
                numThreads = 2
            }

            val config = KeywordSpotterConfig().apply {
                featConfig = FeatureConfig(16000, 80, 0f)
                this.modelConfig = modelConfig
                keywordsFile = keywordFile
                keywordsScore = 1.0f
                keywordsThreshold = 0.3f
                numTrailingBlanks = 1
            }

            // Models are downloaded to external storage (absolute paths), so the Sherpa-ONNX
            // assetManager MUST be null to take the newFromFile() native path. Passing a non-null
            // AssetManager makes Sherpa try to resolve the absolute path through the APK assets,
            // which fails and fatally aborts the process (k2-fsa/sherpa-onnx#2562). See
            // validation evidence artifacts/validation/xiaomi14/.../PHASE6-LANGUAGE-PACKS.md.
            val kws = KeywordSpotter(null, config)
            spotter = kws
            stream = kws.createStream()
            initialized = true
            Logger.i("KeywordSpotterEngine: Offline KWS initialized (${keywordEntries.size} phrases)")
            Result.Success(Unit)
        } catch (e: Throwable) {
            Logger.e("KeywordSpotterEngine: Initialization failed: ${e::class.java.simpleName}: ${e.message}")
            spotter = null
            stream = null
            initialized = false
            Result.Error("KWS unavailable: ${e.message}")
        }
    }

    fun processChunk(pcmBytes: ByteArray): String? {
        val kws = spotter
        if (!initialized || kws == null) return null

        return try {
            val samples = pcmToFloat(pcmBytes)
            val s = stream ?: kws.createStream().also { stream = it }
            s.acceptWaveform(samples, 16000)
            kws.decode(s)
            val result = kws.getResult(s)
            val keyword = result.keyword.trim()
            if (keyword.isNotBlank()) {
                Logger.i("KeywordSpotterEngine: Wake word detected: '$keyword'")
                // Reset decoder state so the same wake word can fire again.
                try {
                    s.release()
                } catch (_: Throwable) {
                }
                stream = kws.createStream()
                keyword
            } else {
                null
            }
        } catch (e: Throwable) {
            Logger.e("KeywordSpotterEngine: Error processing chunk: ${e::class.java.simpleName}: ${e.message}")
            null
        }
    }

    private fun createKeywordFile(keywordEntries: List<String>): String {
        // Use cacheDir (caller-provided, usually context.cacheDir) — deleteOnExit is unreliable on
        // Android (only runs on clean JVM shutdown). We delete in release() instead.
        val dir = if (cacheDir != null) File(cacheDir) else File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        dir.mkdirs()
        val file = File(dir, "unoone_keywords.txt")
        file.writeText(keywordEntries.joinToString("\n"))
        keywordFilePath = file.absolutePath
        return file.absolutePath
    }

    fun release() {
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = null
        try {
            spotter?.release()
        } catch (e: Throwable) {
            Logger.e("KeywordSpotterEngine: Error closing spotter", e)
        }
        spotter = null
        initialized = false

        keywordFilePath?.let {
            try {
                File(it).delete()
            } catch (_: Exception) {
            }
        }
        keywordFilePath = null
    }

    private fun pcmToFloat(pcmBytes: ByteArray): FloatArray {
        val samples = FloatArray(pcmBytes.size / 2)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = buffer.short.toFloat() / 32768f
        }
        return samples
    }
}

/** Returns keyword-file tokens that are absent from a Sherpa `tokens.txt` file. */
internal fun missingKeywordTokens(
    keywordEntries: List<String>,
    tokenLines: List<String>
): Set<String> {
    val modelTokens = tokenLines.mapNotNull { line ->
        line.substringBeforeLast(' ', "").takeIf { it.isNotEmpty() }
    }.toHashSet()
    return keywordEntries.asSequence()
        .flatMap { it.trim().split(Regex("\\s+")).asSequence() }
        .filterNot { it.startsWith(":") || it.startsWith("#") || it.startsWith("@") }
        .filterNot { it in modelTokens }
        .toSortedSet()
}
