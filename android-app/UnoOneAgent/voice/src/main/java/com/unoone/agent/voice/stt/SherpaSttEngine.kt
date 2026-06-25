package com.unoone.agent.voice.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production offline STT using Sherpa-ONNX **streaming** transducer models
 * (encoder/decoder/joiner + tokens).
 *
 * Uses the Online (streaming) recognizer rather than the Offline one because the only public,
 * ungated English transducer on Hugging Face is the streaming zipformer int8
 * (`csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`). The offline English transducer
 * repos are gated. A streaming recognizer fed a whole utterance at once and drained with
 * `while (isReady) decode` behaves as a single-shot transcriber and preserves this engine's
 * `transcribe(pcmBytes) -> Result<String>` contract. The wake-word [KeywordSpotterEngine] uses the
 * same Online model family, so STT and KWS share one model install.
 *
 * Real direct-API implementation (no reflection). The native AAR is pulled in via the
 * `com.github.k2-fsa:sherpa-onnx` Maven coordinate declared in :voice/build.gradle.kts, or a
 * dropped-in `voice/libs/sherpa-onnx.aar`. If the native `.so` fails to load on a given device,
 * initialization degrades gracefully (returns Result.Error) so the caller can fall back to the
 * emergency Android SpeechRecognizer path — it never crashes.
 */
class SherpaSttEngine(private val context: Context, private val modelDir: String) {

    @Volatile
    private var recognizer: OnlineRecognizer? = null
    @Volatile
    private var initialized = false

    /**
     * Best-effort confidence for the last transcription. Sherpa streaming transducer results do not
     * expose a confidence score, so this is 1.0 when text is produced and 0.0 when empty — enough
     * to drive the orchestrator's low-confidence retry prompt.
     */
    @Volatile
    var lastConfidence: Float = 1f
        private set

    @Synchronized
    fun initialize(): Result<Unit> {
        return try {
            Logger.i("SherpaSttEngine: Checking model files in $modelDir")
            val encoder = File("$modelDir/encoder.onnx")
            val decoder = File("$modelDir/decoder.onnx")
            val joiner = File("$modelDir/joiner.onnx")
            val tokens = File("$modelDir/tokens.txt")

            if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                return Result.Error("Sherpa STT model files missing. Please download models to: $modelDir")
            }

            val onlineModelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder.absolutePath,
                    decoder.absolutePath,
                    joiner.absolutePath
                )
                this.tokens = tokens.absolutePath
                numThreads = 4
            }

            val config = OnlineRecognizerConfig().apply {
                featConfig = FeatureConfig(16000, 80, 0f)
                this.modelConfig = onlineModelConfig
                // We transcribe whole utterances, not a live mic feed, so endpoint detection is not
                // useful here; disable it so the recognizer does not cut off mid-utterance.
                enableEndpoint = false
            }

            recognizer = OnlineRecognizer(context.assets, config)
            initialized = true
            Logger.i("SherpaSttEngine: Online STT initialized (streaming transducer, 4 threads)")
            Result.Success(Unit)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (native .so missing/incompatible) is an Error, not Exception.
            Logger.e("SherpaSttEngine: Initialization failed: ${e::class.java.simpleName}: ${e.message}")
            recognizer = null
            initialized = false
            Result.Error("Sherpa STT unavailable: ${e.message}")
        }
    }

    @Synchronized
    fun transcribe(pcmBytes: ByteArray): Result<String> {
        val rec = recognizer
        if (!initialized || rec == null) {
            return Result.Error("SherpaSttEngine not initialized")
        }
        if (pcmBytes.size < 2) {
            lastConfidence = 0f
            return Result.Error("No audio captured")
        }

        var stream: OnlineStream? = null
        return try {
            val samples = pcmToFloat(pcmBytes)
            stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            // Drain the streaming decoder: feed the whole utterance at once, then keep decoding
            // while frames remain queued. This turns the streaming recognizer into a single-shot
            // transcriber (equivalent to offline decoding) for a complete captured clip.
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val result = rec.getResult(stream)
            val text = result.text.trim()
            lastConfidence = if (text.isNotBlank()) 1f else 0f
            Logger.i("SherpaSttEngine: Transcribed: '$text'")
            Result.Success(text)
        } catch (e: Throwable) {
            Logger.e("SherpaSttEngine: Transcription failed: ${e::class.java.simpleName}: ${e.message}")
            lastConfidence = 0f
            Result.Error("Transcription failed: ${e.message}")
        } finally {
            try {
                stream?.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun isInitialized(): Boolean = initialized

    @Synchronized
    fun release() {
        try {
            recognizer?.release()
        } catch (e: Throwable) {
            Logger.e("SherpaSttEngine: Error releasing recognizer", e)
        }
        recognizer = null
        initialized = false
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