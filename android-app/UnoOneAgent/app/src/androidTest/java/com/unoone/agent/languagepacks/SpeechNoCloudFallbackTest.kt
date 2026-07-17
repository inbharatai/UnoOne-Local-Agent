package com.unoone.agent.languagepacks

import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.stt.SttMode
import com.unoone.agent.voice.tts.SherpaTtsEngine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real on-device, HEADLESS "no silent cloud fallback" gate (DEVICE_VERIFICATION §5 "no system/cloud
 * speech fallback occurs unless explicitly enabled"). Constructing each Sherpa-ONNX engine against a
 * NON-EXISTENT model directory must return [Result.Error] citing missing files — it must never fall
 * back to the platform Android SpeechRecognizer (cloud) or otherwise report success. This proves the
 * speech path is offline-only. No model files are touched and no native runtime is loaded (the engines
 * check file existence before any native construction), so this is safe and fast.
 *
 * Run: am instrument -e class com.unoone.agent.languagepacks.SpeechNoCloudFallbackTest ...
 */
class SpeechNoCloudFallbackTest {

    private val missingDir = "/data/local/tmp/unoone-nonexistent-speech-models"

    @Test
    fun sttTransducerRejectsMissingModelsNotCloudFallback() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = SherpaSttEngine(ctx, missingDir, SttMode.TRANSDUCER, "en").initialize()
        assertTrue("transducer STT must return Error (not Success/cloud fallback): $r", r is Result.Error)
        assertTrue(
            "error must cite missing model files: ${(r as Result.Error).message}",
            (r as Result.Error).message!!.lowercase().contains("missing")
        )
    }

    @Test
    fun sttWhisperRejectsMissingModelsNotCloudFallback() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = SherpaSttEngine(ctx, missingDir, SttMode.WHISPER, "hi").initialize()
        assertTrue("whisper STT must return Error (not Success/cloud fallback): $r", r is Result.Error)
        assertTrue(
            "error must cite missing model files: ${(r as Result.Error).message}",
            (r as Result.Error).message!!.lowercase().contains("missing")
        )
    }

    @Test
    fun sttOmnilingualRejectsMissingModelsNotCloudFallback() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = SherpaSttEngine(ctx, missingDir, SttMode.OMNILINGUAL, "hi").initialize()
        assertTrue("omnilingual STT must return Error (not Success/cloud fallback): $r", r is Result.Error)
        assertTrue(
            "error must cite missing model files: ${(r as Result.Error).message}",
            (r as Result.Error).message!!.lowercase().contains("missing")
        )
    }

    @Test
    fun ttsRejectsMissingModelsNotCloudFallback() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val r = SherpaTtsEngine(ctx, missingDir).initialize()
        assertTrue("TTS must return Error (not Success/cloud fallback): $r", r is Result.Error)
        assertTrue(
            "error must cite missing model files: ${(r as Result.Error).message}",
            (r as Result.Error).message!!.lowercase().contains("missing")
        )
    }
}
