package com.unoone.agent.phonecontrol

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OcrControl(private val context: Context) {

    // Lazily initialized so ML Kit is only spun up if OCR is actually used — avoids the cost (and
    // the MlKitContext requirement) on devices/paths that never run OCR, and lets this class be
    // constructed in unit tests.
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val screenshotCapture = ScreenshotCapture(context)

    suspend fun recognizeText(bitmap: Bitmap): Result<String> = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                Logger.d("OCR Success: ${visionText.text.take(20)}...")
                continuation.resume(Result.Success(visionText.text))
            }
            .addOnFailureListener { e ->
                Logger.e("OCR Failed", e)
                continuation.resume(Result.Error("Failed to read text from screen: ${e.message}"))
            }
    }

    /**
     * Captures the current screen via MediaProjection and runs OCR on it.
     * If projection permission has not been granted yet, this returns an error so the UI layer
     * can launch [com.unoone.agent.screenshot.ScreenshotPermissionActivity].
     */
    suspend fun recognizeScreen(): Result<String> {
        if (!ScreenshotCapture.hasPermission()) {
            return Result.Error("Screenshot OCR requires MediaProjection permission")
        }
        return when (val bitmapResult = screenshotCapture.captureScreen()) {
            is Result.Success -> recognizeText(bitmapResult.data)
            is Result.Error -> Result.Error(bitmapResult.message)
        }
    }

    /**
     * Release the ML Kit text recognizer to prevent memory leaks.
     * Call this when the OcrControl is no longer needed.
     */
    fun release() {
        try {
            recognizer.close()
            Logger.i("OcrControl: Recognizer released")
        } catch (e: Exception) {
            Logger.e("OcrControl: Error releasing recognizer", e)
        }
    }
}
