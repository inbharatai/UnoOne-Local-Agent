package com.unoone.agent.voice.stt

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
// import com.k2fsa.sherpa.onnx.OfflineRecognizer
// import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
// import com.k2fsa.sherpa.onnx.OfflineModelConfig
// import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
// import com.k2fsa.sherpa.onnx.Wave
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaSttEngine(private val modelDir: String) {

    // private var recognizer: OfflineRecognizer? = null
    private var initialized = false

    fun initialize(): Result<Unit> {
        return try {
            // val config = OfflineRecognizerConfig(
            //     modelConfig = OfflineModelConfig(
            //         transducer = OfflineTransducerModelConfig(
            //             encoder = "$modelDir/encoder.onnx",
            //             decoder = "$modelDir/decoder.onnx",
            //             joiner = "$modelDir/joiner.onnx"
            //         ),
            //         tokens = "$modelDir/tokens.txt",
            //         numThreads = 4,
            //         provider = "cpu"
            //     )
            // )
            // recognizer = OfflineRecognizer(config)
            initialized = true
            Logger.i("SherpaSttEngine initialized successfully (STUBBED)")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("SherpaSttEngine init failed: ${e.message}", e)
            Result.Error("STT init failed: ${e.message}")
        }
    }

    fun transcribe(pcmBytes: ByteArray): Result<String> {
        if (!initialized) return Result.Error("STT engine not initialized")
        // val rec = recognizer ?: return Result.Error("STT recognizer is null")

        return try {
            // val samples = FloatArray(pcmBytes.size / 2)
            // val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            // for (i in samples.indices) {
            //     samples[i] = buffer.short.toFloat() / 32768f
            // }

            // val wave = Wave(samples = samples, sampleRate = 16000f)
            // val result = rec.decode(wave)
            // val text = result.text.trim()
            // Logger.i("STT result: '$text'")
            // Result.Success(text)
            Result.Success("Test command from STT stub")
        } catch (e: Exception) {
            Logger.e("STT transcription failed", e)
            Result.Error("Transcription failed: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = initialized

    fun release() {
        // try {
        //     recognizer?.close()
        // } catch (e: Exception) {
        //     Logger.e("Error releasing STT engine", e)
        // }
        // recognizer = null
        initialized = false
        Logger.i("STT engine released")
    }
}