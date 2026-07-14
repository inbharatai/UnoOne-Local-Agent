package com.unoone.agent.phonecontrol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real on-device, HEADLESS proof of [OcrControl] (the bundled on-device ML Kit Latin text
 * recognizer — no model download, no network). Constructs the real [OcrControl] and:
 *
 * - `recognizeText(Bitmap)`: renders a known Latin string onto a synthetic high-contrast Bitmap
 *   with the platform Canvas, runs the actual ML Kit recognizer, and asserts the recognized text
 *   contains the rendered string. This proves the bundled OCR pipeline executes end-to-end on the
 *   device without MediaProjection / Accessibility / camera — pure on-device ML Kit.
 * - `recognizeScreen()`: with no MediaProjection permission granted in this headless process,
 *   returns a [Result.Error] (the pre-capture gate fires). Proves the system-access gate is honored
 *   before any screenshot is attempted.
 *
 * Honesty: this proves OCR runs on the device against rendered text. It does NOT prove on-screen /
 * live-screenshot OCR accuracy against a real app screen — that needs MediaProjection, which is a
 * live-UI gate and remains a manual device item (see DEVICE_VERIFICATION.md).
 *
 * Run: am instrument -e class com.unoone.agent.phonecontrol.OcrControlHeadlessTest \
 *   com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
 */
class OcrControlHeadlessTest {

    private lateinit var ocr: OcrControl

    @Before
    fun setUp() {
        ocr = OcrControl(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        ocr.release()
    }

    @Test
    fun recognizesRenderedLatinText() = runBlocking {
        val rendered = "UNOONE OCR"
        val bitmap = renderText(rendered, width = 1000, height = 320, textSizePx = 96f)

        val result = ocr.recognizeText(bitmap)

        assertTrue("recognizeText must succeed on a rendered bitmap (got: $result)", result is Result.Success)
        val text = (result as Result.Success).data
        assertTrue(
            "Bundled ML Kit must recognize the rendered Latin text on the device. " +
                "expected to contain '$rendered', got: \"$text\"",
            text.uppercase().replace(" ", "").contains(rendered.replace(" ", ""))
        )
    }

    @Test
    fun recognizeScreenRequiresProjectionWhenNotGranted() = runBlocking {
        val result = ocr.recognizeScreen()
        // No MediaProjection permission has been granted in this headless process, so the
        // pre-capture gate must fire (or, if a stale flag were set, captureScreen() fails without
        // an active projection). Either way this is an error, never a silent empty success.
        assertFalse(
            "recognizeScreen must NOT succeed headlessly without MediaProjection (got: $result)",
            result is Result.Success && (result as Result.Success).data.isNotBlank()
        )
        assertTrue("recognizeScreen must return an Error headlessly (got: $result)", result is Result.Error)
    }

    private fun renderText(text: String, width: Int, height: Int, textSizePx: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val fm = paint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        val textWidth = paint.measureText(text)
        canvas.drawText(text, (width - textWidth) / 2f, baseline, paint)
        return bmp
    }
}