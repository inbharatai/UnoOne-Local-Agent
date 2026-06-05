package com.unoone.agent.phonecontrol

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Expert: Offline Object & Barrier Detection using Google ML Kit.
 * Runs entirely on-device with zero network latency.
 */
class ObjectDetectionControl(private val context: Context) {

    // Configure for multiple objects and classification (e.g., detecting obstacles, doors, chairs, people)
    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .enableMultipleObjects()
        .enableClassification() // Detect what the object is
        .build()

    private val detector = ObjectDetection.getClient(options)

    suspend fun detectObjects(bitmap: Bitmap): Result<List<DetectedObjectInfo>> = suspendCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { detectedObjects: List<DetectedObject> ->
                    val resultList = detectedObjects.map { obj ->
                        val category = obj.labels.firstOrNull()?.text ?: "Unknown Object"
                        val confidence = obj.labels.firstOrNull()?.confidence ?: 0f
                        val box = obj.boundingBox
                        
                        // Heuristic for barrier detection based on bounding box size
                        val isPotentialBarrier = box.width() > bitmap.width * 0.5 && box.height() > bitmap.height * 0.5
                        
                        DetectedObjectInfo(
                            label = category,
                            confidence = confidence,
                            isBarrier = isPotentialBarrier,
                            boundingBoxString = "[L:${box.left}, T:${box.top}, R:${box.right}, B:${box.bottom}]"
                        )
                    }
                    Logger.d("Offline Object Detection: Found ${resultList.size} objects")
                    continuation.resume(Result.Success(resultList))
                }
                .addOnFailureListener { e ->
                    Logger.e("Offline Object Detection failed", e)
                    continuation.resume(Result.Error("Object detection failed: ${e.message}"))
                }
        } catch (e: Exception) {
            Logger.e("Error preparing image for detection", e)
            continuation.resume(Result.Error("Failed to process image: ${e.message}"))
        }
    }

    data class DetectedObjectInfo(
        val label: String,
        val confidence: Float,
        val isBarrier: Boolean,
        val boundingBoxString: String
    )

    /**
     * Release the ML Kit object detector to prevent memory leaks.
     * Call this when ObjectDetectionControl is no longer needed.
     */
    fun release() {
        try {
            detector.close()
            Logger.i("ObjectDetectionControl: Detector released")
        } catch (e: Exception) {
            Logger.e("ObjectDetectionControl: Error releasing detector", e)
        }
    }
}
