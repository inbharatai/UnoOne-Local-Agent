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

            tts = OfflineTts(context.assets, config)
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