package com.unoone.agent.voice.stt

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
// import com.k2fsa.sherpa.onnx.KeywordSpotter
// import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
// import com.k2fsa.sherpa.onnx.OfflineModelConfig
// import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
// import com.k2fsa.sherpa.onnx.Wave
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KeywordSpotterEngine(private val modelDir: String) {

    // private var spotter: KeywordSpotter? = null
    private var initialized = false

    fun initialize(keywords: List<String>): Result<Unit> {
        return try {
            // val keywordFile = createKeywordFile(keywords)
            // val config = KeywordSpotterConfig(
            //     modelConfig = OfflineModelConfig(
            //         transducer = OfflineTransducerModelConfig(
            //             encoder = "$modelDir/encoder.onnx",
            //             decoder = "$modelDir/decoder.onnx",
            //             joiner = "$modelDir/joiner.onnx"
            //         ),
            //         tokens = "$modelDir/tokens.txt",
            //         numThreads = 2,
            //         provider = "cpu"
            //     ),
            //     keywordFile = keywordFile
            // )
            // spotter = KeywordSpotter(config)
            initialized = true
            Logger.i("KeywordSpotter initialized with keywords: $keywords (STUBBED)")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("KeywordSpotter init failed: ${e.message}", e)
            Result.Error("KWS init failed: ${e.message}")
        }
    }

    fun processChunk(pcmBytes: ByteArray): String? {
        if (!initialized) return null
        // val kws = spotter ?: return null

        return try {
            // val samples = FloatArray(pcmBytes.size / 2)
            // val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            // for (i in samples.indices) {
            //     samples[i] = buffer.short.toFloat() / 32768f
            // }

            // val wave = Wave(samples = samples, sampleRate = 16000f)
            // val keyword = kws.decode(wave)
            // if (keyword.isNotBlank()) {
            //     Logger.i("Wake word detected: $keyword")
            //     keyword
            // } else {
            //     null
            // }
            null
        } catch (e: Exception) {
            Logger.e("KWS process error", e)
            null
        }
    }

    private fun createKeywordFile(keywords: List<String>): String {
        val file = java.io.File.createTempFile("keywords", ".txt")
        file.writeText(keywords.joinToString("\n") { it })
        file.deleteOnExit()
        return file.absolutePath
    }

    fun release() {
        // try {
        //     spotter?.close()
        // } catch (e: Exception) {
        //     Logger.e("Error releasing KeywordSpotter", e)
        // }
        // spotter = null
        initialized = false
        Logger.i("KeywordSpotter released")
    }
}