package com.unoone.agent.voice.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
 * Which Sherpa model family the engine loads for a given language.
 *
 * - [TRANSDUCER]: streaming zipformer transducer (encoder/decoder/joiner + tokens). Used for
 *   English — the only public, ungated Indic-relevant transducer is the English streaming one, which
 *   the wake-word [KeywordSpotterEngine] also shares. Decoded in streaming mode (drained with
 *   `while (isReady) decode`) which behaves as single-shot for a whole captured clip.
 * - [WHISPER]: supported for compatibility and model evaluation.
 * - [OMNILINGUAL]: Sherpa Omnilingual CTC (model + tokens). Used for the enabled Indian languages
 *   after native-script TTS→STT qualification on the primary device.
 */
enum class SttMode { TRANSDUCER, WHISPER, OMNILINGUAL }

/**
 * Production offline STT using Sherpa-ONNX. Supports three model families via [mode]:
 *
 * - **TRANSDUCER** (streaming zipformer, English): `OnlineRecognizer` fed a whole utterance and
 *   drained with `while (isReady) decode`. The only public, ungated English transducer on Hugging
 *   Face is the streaming zipformer int8 (`csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26`)
 *   — the offline repos are gated. The wake-word [KeywordSpotterEngine] uses the same Online family,
 *   so STT and KWS share one model install.
 * - **WHISPER**: compatibility/evaluation path using `OfflineWhisperModelConfig`.
 * - **OMNILINGUAL** (enabled Indian languages): `OfflineRecognizer` with
 *   `OfflineOmnilingualAsrCtcModelConfig`. One-shot decode with automatic spoken-language/script
 *   recognition; the selected UnoOne language still controls routing, replies, and TTS.
 *
 * Real direct-API implementation (no reflection). The native AAR is pulled in via the
 * `com.github.k2-fsa:sherpa-onnx` Maven coordinate declared in :voice/build.gradle.kts, or a
 * dropped-in `voice/libs/sherpa-onnx.aar`. If the native `.so` fails to load on a given device,
 * initialization degrades gracefully (returns Result.Error) so the caller can fall back to the
 * emergency Android SpeechRecognizer path — it never crashes.
 *
 * @param language the Whisper language code. Ignored in TRANSDUCER and OMNILINGUAL modes.
 */
class SherpaSttEngine(
    private val context: Context,
    private val modelDir: String,
    private val mode: SttMode = SttMode.TRANSDUCER,
    private val language: String = "en"
) {

    @Volatile
    private var onlineRecognizer: OnlineRecognizer? = null
    @Volatile
    private var offlineRecognizer: OfflineRecognizer? = null
    @Volatile
    private var initialized = false

    /**
     * Best-effort confidence for the last transcription. Sherpa results do not expose a numeric
     * confidence, so this is 1.0 when text is produced and 0.0 when empty — enough to drive the
     * orchestrator's low-confidence retry prompt.
     */
    @Volatile
    var lastConfidence: Float = 1f
        private set

    @Synchronized
    fun initialize(): Result<Unit> {
        return try {
            when (mode) {
                SttMode.TRANSDUCER -> initializeTransducer()
                SttMode.WHISPER -> initializeWhisper()
                SttMode.OMNILINGUAL -> initializeOmnilingual()
            }
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (native .so missing/incompatible) is an Error, not Exception.
            Logger.e("SherpaSttEngine: Initialization failed (${mode}): ${e::class.java.simpleName}: ${e.message}")
            onlineRecognizer = null
            offlineRecognizer = null
            initialized = false
            Result.Error("Sherpa STT unavailable: ${e.message}")
        }
    }

    private fun initializeTransducer(): Result<Unit> {
        Logger.i("SherpaSttEngine: Checking transducer files in $modelDir")
        val encoder = File("$modelDir/encoder.onnx")
        val decoder = File("$modelDir/decoder.onnx")
        val joiner = File("$modelDir/joiner.onnx")
        val tokens = File("$modelDir/tokens.txt")
        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            return Result.Error("Sherpa STT (transducer) model files missing. Please download models to: $modelDir")
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
        // Models live on external storage (absolute paths); pass a null assetManager so Sherpa-ONNX
        // takes its newFromFile() native path. A non-null AssetManager makes Sherpa resolve the
        // absolute path through the APK assets, fail, and fatally abort the process
        // (k2-fsa/sherpa-onnx#2562). See Phase 6 device evidence.
        onlineRecognizer = OnlineRecognizer(null, config)
        initialized = true
        Logger.i("SherpaSttEngine: Online STT initialized (streaming transducer, 4 threads)")
        return Result.Success(Unit)
    }

    private fun initializeWhisper(): Result<Unit> {
        val files = resolveWhisperFiles(modelDir)
        Logger.i("SherpaSttEngine: Checking whisper files in $modelDir (lang=$language)")
        if (files == null) {
            return Result.Error("Sherpa STT (whisper) model files missing. Please download the whisper model to: $modelDir")
        }

        val whisperConfig = OfflineWhisperModelConfig(
            files.encoder.absolutePath,
            files.decoder.absolutePath,
            language,
            "transcribe"
        )
        val modelConfig = OfflineModelConfig().apply {
            this.whisper = whisperConfig
            this.tokens = files.tokens.absolutePath
            numThreads = 4
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(16000, 80, 0f)
            this.modelConfig = modelConfig
        }
        // See note above: filesystem (absolute-path) models require a null assetManager so Sherpa
        // uses newFromFile(); a non-null AssetManager fatally aborts (k2-fsa/sherpa-onnx#2562).
        offlineRecognizer = OfflineRecognizer(null, config)
        initialized = true
        Logger.i("SherpaSttEngine: Offline STT initialized (${files.family}, lang=$language, 4 threads)")
        return Result.Success(Unit)
    }

    private fun initializeOmnilingual(): Result<Unit> {
        val files = resolveOmnilingualFiles(modelDir)
        Logger.i("SherpaSttEngine: Checking omnilingual files in $modelDir")
        if (files == null) {
            return Result.Error(
                "Sherpa STT (omnilingual) model files missing. Please download the omnilingual model to: $modelDir"
            )
        }

        val modelConfig = OfflineModelConfig().apply {
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(files.model.absolutePath)
            tokens = files.tokens.absolutePath
            numThreads = 4
        }
        val config = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(16000, 80, 0f)
            this.modelConfig = modelConfig
        }
        offlineRecognizer = OfflineRecognizer(null, config)
        initialized = true
        Logger.i("SherpaSttEngine: Offline STT initialized (omnilingual CTC, 4 threads)")
        return Result.Success(Unit)
    }

    @Synchronized
    fun transcribe(pcmBytes: ByteArray): Result<String> {
        if (!initialized) return Result.Error("SherpaSttEngine not initialized")
        if (pcmBytes.size < 2) {
            lastConfidence = 0f
            return Result.Error("No audio captured")
        }

        return try {
            val samples = pcmToFloat(pcmBytes)
            when (mode) {
                SttMode.TRANSDUCER -> transcribeTransducer(samples)
                SttMode.WHISPER -> transcribeWhisper(samples)
                SttMode.OMNILINGUAL -> transcribeOffline(samples, "omnilingual")
            }
        } catch (e: Throwable) {
            Logger.e("SherpaSttEngine: Transcription failed (${mode}): ${e::class.java.simpleName}: ${e.message}")
            lastConfidence = 0f
            Result.Error("Transcription failed: ${e.message}")
        }
    }

    private fun transcribeTransducer(samples: FloatArray): Result<String> {
        val rec = onlineRecognizer ?: return Result.Error("Sherpa STT (transducer) not initialized")
        var stream: OnlineStream? = null
        return try {
            stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            // Drain the streaming decoder: feed the whole utterance at once, then keep decoding
            // while frames remain queued. This turns the streaming recognizer into a single-shot
            // transcriber (equivalent to offline decoding) for a complete captured clip.
            while (rec.isReady(stream)) {
                rec.decode(stream)
            }
            val text = rec.getResult(stream).text.trim()
            lastConfidence = if (text.isNotBlank()) 1f else 0f
            Logger.i("SherpaSttEngine: Transcribed (transducer, chars=${text.length})")
            Result.Success(text)
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
        }
    }

    private fun transcribeWhisper(samples: FloatArray): Result<String> {
        return transcribeOffline(samples, "whisper/$language")
    }

    private fun transcribeOffline(samples: FloatArray, family: String): Result<String> {
        val rec = offlineRecognizer ?: return Result.Error("Sherpa STT (offline) not initialized")
        var stream: OfflineStream? = null
        return try {
            stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            // Whisper is decoded one-shot: feed the whole utterance, decode once, read the result.
            // Unlike the streaming transducer there is no isReady() drain loop.
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            lastConfidence = if (text.isNotBlank()) 1f else 0f
            Logger.i("SherpaSttEngine: Transcribed ($family, chars=${text.length})")
            Result.Success(text)
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
        }
    }

    fun isInitialized(): Boolean = initialized

    @Synchronized
    fun release() {
        try { onlineRecognizer?.release() } catch (e: Throwable) { Logger.e("SherpaSttEngine: Error releasing online recognizer", e) }
        try { offlineRecognizer?.release() } catch (e: Throwable) { Logger.e("SherpaSttEngine: Error releasing offline recognizer", e) }
        onlineRecognizer = null
        offlineRecognizer = null
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

    companion object {
        /** The top directory the whisper-tiny tarball extracts to inside a model folder. */
        const val WHISPER_TOP_DIR = "sherpa-onnx-whisper-tiny"

        data class WhisperFiles(
            val encoder: File,
            val decoder: File,
            val tokens: File,
            val family: String
        )

        data class OmnilingualFiles(
            val model: File,
            val tokens: File
        )

        /**
         * Resolves any official sherpa-onnx multilingual Whisper family (tiny/base/small/medium).
         * Int8 files are preferred for a mobile CPU; all three files must share the same prefix.
         */
        fun resolveWhisperFiles(modelDir: String): WhisperFiles? {
            val root = File(modelDir)
            val directories = buildList {
                add(root)
                root.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && it.name.startsWith("sherpa-onnx-whisper-") }
                    ?.sortedBy { it.name }
                    ?.forEach(::add)
            }
            for (directory in directories) {
                val tokenFiles = directory.listFiles()
                    ?.filter { it.isFile && it.name.endsWith("-tokens.txt") }
                    ?.sortedBy { it.name }
                    .orEmpty()
                for (tokens in tokenFiles) {
                    val prefix = tokens.name.removeSuffix("-tokens.txt")
                    val encoder = listOf(
                        File(directory, "$prefix-encoder.int8.onnx"),
                        File(directory, "$prefix-encoder.onnx")
                    ).firstOrNull(File::isFile)
                    val decoder = listOf(
                        File(directory, "$prefix-decoder.int8.onnx"),
                        File(directory, "$prefix-decoder.onnx")
                    ).firstOrNull(File::isFile)
                    if (encoder != null && decoder != null) {
                        return WhisperFiles(encoder, decoder, tokens, prefix)
                    }
                }
            }
            return null
        }

        /** Resolves the official Sherpa Omnilingual CTC archive or a flattened install. */
        fun resolveOmnilingualFiles(modelDir: String): OmnilingualFiles? {
            val root = File(modelDir)
            val directories = buildList {
                add(root)
                root.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && it.name.startsWith("sherpa-onnx-omnilingual-asr-") }
                    ?.sortedBy { it.name }
                    ?.forEach(::add)
            }
            return directories.firstNotNullOfOrNull { directory ->
                val model = listOf(
                    File(directory, "model.int8.onnx"),
                    File(directory, "model.onnx")
                ).firstOrNull(File::isFile)
                val tokens = File(directory, "tokens.txt").takeIf(File::isFile)
                if (model != null && tokens != null) OmnilingualFiles(model, tokens) else null
            }
        }

        /**
         * Resolves the whisper model directory (the tarball's extracted top dir) inside [modelDir].
         * Pure/testable — does not touch the native runtime.
         */
        fun whisperModelDir(modelDir: String): File = File(modelDir, WHISPER_TOP_DIR)

        /**
         * The files a whisper install requires under [modelDir]. Pure/testable — used by the engine
         * and by tests that verify the expected layout without instantiating the native runtime.
         */
        fun whisperRequiredFiles(modelDir: String): List<File> {
            val dir = whisperModelDir(modelDir)
            return listOf(
                File(dir, "tiny-encoder.int8.onnx"),
                File(dir, "tiny-decoder.int8.onnx"),
                File(dir, "tiny-tokens.txt")
            )
        }

        /** The files a transducer install requires directly under [modelDir]. Pure/testable. */
        fun transducerRequiredFiles(modelDir: String): List<File> = listOf(
            File(modelDir, "encoder.onnx"),
            File(modelDir, "decoder.onnx"),
            File(modelDir, "joiner.onnx"),
            File(modelDir, "tokens.txt")
        )
    }
}
