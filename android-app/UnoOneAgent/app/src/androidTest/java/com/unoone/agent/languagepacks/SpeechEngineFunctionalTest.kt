package com.unoone.agent.languagepacks

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.voice.tts.SherpaTtsEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.stt.SttMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Functional speech gate for the two production voice languages: English and Hindi.
 *
 * Goes beyond "files present + sha verified" to prove the Sherpa-ONNX engines actually RUN on the
 * Xiaomi 14:
 *  - TTS: for English and Hindi, construct SherpaTtsEngine, initialize(), speak(sample).
 *    speak() returns Success only when the VITS model produces a non-empty PCM buffer — so Success
 *    proves real ONNX synthesis end-to-end (model loads -> inference -> PCM samples generated).
 *  - STT: initialize() the English transducer and the multilingual Omnilingual recognizer. Success
 *    proves the ONNX ASR models load on device. Actual transcription needs microphone PCM, which is
 *    a manual/physical gate (noted, not asserted here).
 *
 * Depends on the install harness having run first (Phase 6a). Logs tagged UnoOneSpeech.
 */
class SpeechEngineFunctionalTest {

    private val tag = "UnoOneSpeech"

    private data class Lang(val code: String, val folder: String, val sample: String)

    private val langs = listOf(
        Lang("en", "en-IN", "Hello, this is an offline speech test on the Xiaomi 14."),
        Lang("hi", "hi-IN", "नमस्ते, यह एक ऑफ़लाइन आवाज़ परीक्षण है।")
    )

    @Test
    fun ttsAllLanguagesAndSttEnginesInit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = ModelManager(context).run {
            ensureModelDirectories()
            // appPrivateModelPath (getExternalFilesDir("models"))
            val f = context.getExternalFilesDir("models")?.absolutePath
                ?: context.filesDir.resolve("models").absolutePath
            f
        }
        Log.i(tag, "==== UNOONE SPEECH FUNCTIONAL TEST ==== base=$base")
        val ttsFailures = mutableListOf<String>()

        for (l in langs) {
            val ttsDir = "$base/speech/languages/${l.folder}/tts"
            Log.i(tag, ">>> TTS ${l.code} dir=$ttsDir")
            val engine = SherpaTtsEngine(context, ttsDir)
            val init = engine.initialize()
            if (init is Result.Error) {
                Log.e(tag, "<<< TTS ${l.code} INIT FAIL: ${init.message}")
                ttsFailures += "${l.code}(init: ${init.message})"
                continue
            }
            val speak = engine.speak(l.sample)
            val ok = speak is Result.Success
            Log.i(tag, "<<< TTS ${l.code} speak=${speak.javaClass.simpleName} ${if (ok) "(PCM generated)" else "-> " + (speak as Result.Error).message}")
            if (!ok) ttsFailures += "${l.code}(speak: ${(speak as Result.Error).message})"
            engine.release()
        }

        // STT engine init (model load only; no mic PCM).
        val sttFailures = mutableListOf<String>()
        Log.i(tag, ">>> STT English transducer init")
        val enStt = SherpaSttEngine(context, "$base/speech/shared/sherpa-asr-en", SttMode.TRANSDUCER, "en")
        val enInit = enStt.initialize()
        Log.i(tag, "<<< STT en transducer init=${enInit.javaClass.simpleName} ${if (enInit is Result.Error) enInit.message else "(online recognizer loaded)"}")
        if (enInit is Result.Error) sttFailures += "en-transducer(${enInit.message})"
        try { /* release if available */ } catch (_: Throwable) {}

        Log.i(tag, ">>> STT Omnilingual init")
        val indicStt = SherpaSttEngine(context, "$base/speech/shared/sherpa-asr-indic", SttMode.OMNILINGUAL, "hi")
        val indicInit = indicStt.initialize()
        Log.i(tag, "<<< STT omnilingual init=${indicInit.javaClass.simpleName} ${if (indicInit is Result.Error) indicInit.message else "(offline omnilingual recognizer loaded)"}")
        if (indicInit is Result.Error) sttFailures += "omnilingual(${indicInit.message})"

        Log.i(tag, "==== END SPEECH TEST — ttsFailures=${ttsFailures.size} sttFailures=${sttFailures.size} ====")
        if (ttsFailures.isNotEmpty()) ttsFailures.forEach { Log.e(tag, "TTS FAIL: $it") }
        if (sttFailures.isNotEmpty()) sttFailures.forEach { Log.e(tag, "STT FAIL: $it") }
        assertTrue("TTS failures: ${ttsFailures.joinToString()}", ttsFailures.isEmpty())
        assertTrue("STT init failures: ${sttFailures.joinToString()}", sttFailures.isEmpty())
    }
}
