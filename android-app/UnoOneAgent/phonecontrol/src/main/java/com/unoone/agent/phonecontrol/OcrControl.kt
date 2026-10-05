package com.unoone.agent.phonecontrol

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OcrControl(private val context: Context) {

    // Lazily initialized so ML Kit is only spun up if OCR is actually used — avoids the cost (and
    // the MlKitContext requirement) on devices/paths that never run OCR, and lets this class be
    // constructed in unit tests.
    // Bundled model: no Play Services download and no network. It recognizes Latin plus
    // Devanagari, matching UnoOne's currently supported English/Hindi language surface.
    private val recognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val screenshotCapture = ScreenshotCapture(context)

    suspend fun recognizeText(bitmap: Bitmap): Result<String> = suspendCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Never log OCR screen contents.
                continuation.resume(Result.Success(com.unoone.agent.core.device.SensitiveReadRedaction.redactText(visionText.text)))
            }
            .addOnFailureListener { e ->
                Logger.e("OCR Failed", e)
                continuation.resume(Result.Error("Failed to read text from screen: ${e.message}"))
            }
    }

    /** Structured line OCR; confidence 0 means unknown (ML Kit line confidence is not exposed here). */
    suspend fun recognizeRegions(bitmap: Bitmap, transform: ScreenTransform? = null): Result<List<com.unoone.agent.core.device.OcrRegion>> = suspendCoroutine { continuation ->
        try {
            require(transform == null || (transform.outputWidth == bitmap.width && transform.outputHeight == bitmap.height))
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    if (com.unoone.agent.core.device.SensitiveReadRedaction.hasSecretLabel(text.text)) {
                        continuation.resume(Result.Success(emptyList()))
                        return@addOnSuccessListener
                    }
                    val regions = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val left = box.left.coerceIn(0, bitmap.width)
                        val top = box.top.coerceIn(0, bitmap.height)
                        val right = box.right.coerceIn(0, bitmap.width)
                        val bottom = box.bottom.coerceIn(0, bitmap.height)
                        if (right <= left || bottom <= top) return@mapNotNull null
                        val bounds = com.unoone.agent.core.device.RectData(left, top, right, bottom)
                        com.unoone.agent.core.device.OcrRegion(line.text.take(256), transform?.toScreen(bounds) ?: bounds, 0f)
                    }.take(128)
                    continuation.resume(Result.Success(regions))
                }
                .addOnFailureListener { continuation.resume(Result.Error("Structured OCR failed: ${it.message}")) }
        } catch (e: Exception) { continuation.resume(Result.Error("Structured OCR failed: ${e.message}")) }
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
            is Result.Success -> try { recognizeText(bitmapResult.data) } finally { bitmapResult.data.recycle() }
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
