package com.unoone.agent.languagepacks

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import com.unoone.agent.voice.VoiceLanguage
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.stt.SttMode
import com.unoone.agent.voice.tts.SherpaTtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Functional Hindi speech gate. Synthesizes a native-script sentence with the installed Hindi
 * MMS TTS model, feeds the resulting PCM directly into Omnilingual STT, and verifies a non-empty
 * Devanagari transcript. This catches wrong-language routing, empty PCM, and an
 * initialized-but-nonfunctional recognizer without needing network or microphone fixtures.
 */
class IndicSpeechRoundTripTest {

    private data class Case(
        val code: String,
        val phrase: String,
        val script: CharRange
    )

    private val cases = listOf(
        Case("hi", VoiceLanguage.testPhrase("hi"), '\u0900'..'\u097F')
    )

    @Test
    fun indicTtsPcmRoundTripsThroughOmnilingualRecognizer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.resolve("models").absolutePath
        val productionRoot = "$root/${VoiceLanguage.asrSpec("hi").folder}"
        val evaluationRoot = context.filesDir.resolve("omnilingual-eval").absolutePath
        val evaluationFiles = SherpaSttEngine.resolveOmnilingualFiles(evaluationRoot)
        if (SherpaSttEngine.resolveOmnilingualFiles(productionRoot) == null && evaluationFiles != null) {
            val productionTop = java.io.File(
                productionRoot,
                evaluationFiles.model.parentFile!!.name
            ).apply { mkdirs() }
            evaluationFiles.model.copyTo(productionTop.resolve("model.int8.onnx"), overwrite = true)
            evaluationFiles.tokens.copyTo(productionTop.resolve("tokens.txt"), overwrite = true)
        }
        val asrRoot = if (SherpaSttEngine.resolveOmnilingualFiles(productionRoot) != null) {
            productionRoot
        } else {
            evaluationRoot
        }

        val failures = mutableListOf<String>()
        cases.forEach { case ->
            val tts = SherpaTtsEngine(
                context,
                "$root/${VoiceLanguage.ttsFolder(case.code)}"
            )
            val ttsInit = tts.initialize()
            if (ttsInit !is Result.Success) {
                failures += "${case.code}: TTS init failed"
                return@forEach
            }
            val speech = tts.synthesize(case.phrase)
            if (speech !is Result.Success) {
                failures += "${case.code}: TTS synthesis failed"
                tts.release()
                return@forEach
            }
            val audio = (speech as Result.Success).data
            assertEquals("${case.code} MMS TTS must provide ASR-ready 16 kHz PCM", 16_000, audio.sampleRate)

            val stt = SherpaSttEngine(
                context,
                asrRoot,
                SttMode.OMNILINGUAL,
                case.code
            )
            val sttInit = stt.initialize()
            if (sttInit !is Result.Success) {
                failures += "${case.code}: Omnilingual init failed"
                tts.release()
                return@forEach
            }
            val transcript = stt.transcribe(audio.toPcm16())
            if (transcript !is Result.Success) {
                failures += "${case.code}: STT failed"
                stt.release()
                tts.release()
                return@forEach
            }
            val text = (transcript as Result.Success).data
            Log.i("UnoOneSpeech", "round-trip ${case.code}: chars=${text.length}")
            val letters = text.count(Char::isLetter)
            val expectedScriptLetters = text.count { it.isLetter() && it in case.script }
            val scriptRatio = if (letters == 0) 0.0 else expectedScriptLetters.toDouble() / letters
            if (text.isBlank()) failures += "${case.code}: empty transcript"
            else if (scriptRatio < 0.6) {
                // The input is a fixed, non-private test fixture; include its decoded result so a
                // script-routing failure can be diagnosed without ever logging user speech.
                failures += "${case.code}: wrong script ratio=$scriptRatio (fixture transcript=$text)"
            }
            stt.release()
            tts.release()
        }
        assertTrue("Hindi speech round-trip failures: ${failures.joinToString()}", failures.isEmpty())
    }
}
