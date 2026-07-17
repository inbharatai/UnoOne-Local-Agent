package com.unoone.agent.voice.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class SynthesizedSpeech(
    val samples: FloatArray,
    val sampleRate: Int
) {
    fun toPcm16(): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[index * 2] = (value and 0xFF).toByte()
            pcm[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return pcm
    }
}

/**
 * Production offline TTS using Sherpa-ONNX VITS models: `model.onnx` + `tokens.txt`.
 *
 * Supports two frontend families, auto-detected from the model folder contents:
 * - **espeak frontend** (English Coqui `vits-coqui-en-ljspeech`): an `espeak-ng-data/` directory is
 *   present alongside the model → `dataDir` is set to it. This is the original shipped path.
 * - **character frontend** (Indic MMS TTS from `willwade/mms-tts-multilingual-models-onnx`): no
 *   `espeak-ng-data/` directory → `dataDir` is left empty and Sherpa auto-detects the character
 *   frontend from the model metadata. One model per language (Hindi/Bengali/Tamil/Telugu/Kannada/
 *   Malayalam), each ~114 MB.
 *
 * Real direct-API implementation (no reflection). Generates PCM via Sherpa and plays it through
 * the shared [TtsPlayer] (AudioTrack). Degrades gracefully if the native `.so` fails to load.
 */
class SherpaTtsEngine(private val context: Context, private val modelDir: String) {

    private val player = TtsPlayer()
    // Native generation is blocking and ignores coroutine cancellation. Serialize requests and
    // invalidate every pre-stop request so cached Blind Aid speech cannot play after the camera is
    // closed. New speech (including "Blind Aid deactivated") uses the new epoch normally.
    private val synthesisMutex = Mutex()
    private val speechEpoch = AtomicLong(0L)
    @Volatile
    private var tts: OfflineTts? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var lastPlaybackDurationMs = 0L

    @Synchronized
    fun initialize(): Result<Unit> {
        return try {
            Logger.i("SherpaTtsEngine: Checking model files in $modelDir")
            val model = File("$modelDir/model.onnx")
            val tokens = File("$modelDir/tokens.txt")
            if (!model.exists() || !tokens.exists()) {
                return Result.Error("Sherpa TTS model files missing. Please download models to: $modelDir")
            }

            // Auto-detect the frontend: espeak (English Coqui) when its data dir is present,
            // character (Indic MMS) otherwise. Sherpa derives the frontend from the model when
            // dataDir is empty, so the MMS path needs no phoneme table.
            val espeakData = File("$modelDir/espeak-ng-data")
            val espeakFrontend = usesEspeakFrontend(modelDir)

            val vits = OfflineTtsVitsModelConfig().apply {
                this.model = model.absolutePath
                this.tokens = tokens.absolutePath
                dataDir = if (espeakFrontend) espeakData.absolutePath else ""
            }
            val modelConfig = OfflineTtsModelConfig().apply {
                this.vits = vits
                numThreads = 2
            }
            val config = OfflineTtsConfig().apply {
                this.model = modelConfig
            }

            // Models live on external storage (absolute paths); pass a null assetManager so
            // Sherpa-ONNX uses newFromFile(). A non-null AssetManager makes Sherpa resolve the
            // absolute path through the APK assets, fail, and fatally abort the process
            // (k2-fsa/sherpa-onnx#2562). See Phase 6 device evidence.
            tts = OfflineTts(null, config)
            initialized = true
            Logger.i("SherpaTtsEngine: Offline TTS initialized (${if (espeakFrontend) "espeak" else "MMS/character"} frontend, 2 threads)")
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
        return speakAtEpoch(text, speechEpoch.get())
    }

    @Synchronized
    private fun speakAtEpoch(text: String, requestEpoch: Long): Result<Unit> {
        if (text.isBlank()) return Result.Success(Unit)
        if (requestEpoch != speechEpoch.get()) return Result.Success(Unit)

        return when (val synthesis = synthesizeInternal(text)) {
            is Result.Error -> synthesis
            is Result.Success -> {
                val audio = synthesis.data
            // stop() may have run while the native, non-cancellable generate() call was active.
            if (requestEpoch != speechEpoch.get()) return Result.Success(Unit)
                player.playPcm(audio.samples, audio.sampleRate)
            if (requestEpoch != speechEpoch.get()) {
                player.stop()
                return Result.Success(Unit)
            }
            lastPlaybackDurationMs = audio.samples.size.toLong() * 1_000L / audio.sampleRate
            Logger.i("SherpaTtsEngine: Generated ${audio.samples.size} samples @ ${audio.sampleRate}Hz")
            Result.Success(Unit)
            }
        }
    }

    /**
     * Generates offline speech without playing it. Used by the on-device speech round-trip gate and
     * future save/share-audio features; it exercises the exact same native synthesis path as speak.
     */
    @Synchronized
    fun synthesize(text: String): Result<SynthesizedSpeech> = synthesizeInternal(text)

    private fun synthesizeInternal(text: String): Result<SynthesizedSpeech> {
        val engine = tts
        if (!initialized || engine == null) {
            return Result.Error("SherpaTtsEngine not initialized")
        }
        if (text.isBlank()) return Result.Error("Text is empty")
        return try {
            val audio = engine.generate(text, 0, 1.0f)
            if (audio.samples.isEmpty()) {
                Result.Error("TTS produced no audio")
            } else {
                Result.Success(SynthesizedSpeech(audio.samples, audio.sampleRate))
            }
        } catch (e: Throwable) {
            Logger.e("SherpaTtsEngine: Speech generation failed: ${e::class.java.simpleName}: ${e.message}")
            Result.Error("TTS synthesis failed: ${e.message}")
        }
    }

    /** Synthesizes off the UI thread and returns after the generated PCM finishes playing. */
    suspend fun speakAwait(text: String, timeoutMs: Long = 30_000L): Result<Unit> {
        val requestEpoch = speechEpoch.get()
        return synthesisMutex.withLock {
            // Waiting for this mutex is cancellable, unlike waiting on @Synchronized native work.
            if (requestEpoch != speechEpoch.get()) return@withLock Result.Success(Unit)
            val result = withContext(Dispatchers.IO) { speakAtEpoch(text, requestEpoch) }
            if (result is Result.Success && requestEpoch == speechEpoch.get()) {
                delay((lastPlaybackDurationMs + 100L).coerceAtMost(timeoutMs))
            }
            result
        }
    }

    fun isInitialized(): Boolean = initialized

    fun stop() {
        speechEpoch.incrementAndGet()
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

    companion object {
        /** The espeak phoneme-table directory name placed alongside a Coqui (English) VITS model. */
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"

        /**
         * True when the model folder ships an `espeak-ng-data/` directory (the Coqui English
         * frontend). False for MMS Indic models, which use the character frontend and need no
         * phoneme table. Pure/testable — does not touch the native runtime.
         */
        fun usesEspeakFrontend(modelDir: String): Boolean =
            File(modelDir, ESPEAK_DATA_DIR).let { it.exists() && it.isDirectory }
    }
}
