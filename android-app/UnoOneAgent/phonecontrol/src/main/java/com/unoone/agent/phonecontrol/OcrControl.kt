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

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
}
