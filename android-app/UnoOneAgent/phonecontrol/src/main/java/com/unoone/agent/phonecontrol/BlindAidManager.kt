package com.unoone.agent.phonecontrol

import android.annotation.SuppressLint
import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.unoone.agent.core.agent.BlindAidNarrator
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.RectF
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One detected object's bounding box in upright-image normalized coordinates. */
data class DetectedBox(val label: String, val rect: RectF)

/** A frame of Blind Aid detections plus the upright image aspect ratio. */
data class DetectionOverlay(val boxes: List<DetectedBox>, val aspectRatio: Float)

/**
 * Offline Blind Aid navigation system.
 *
 * This subsystem is deliberately independent of Gemma. It must continue detecting obstacles and
 * producing haptic, tone and spoken feedback when the LLM is absent, unloaded or recovering from
 * memory pressure. A custom detector may be installed under `models/vision/blind-aid/`; otherwise
 * the offline ML Kit detector is used.
 */
class BlindAidManager(
    private val context: Context,
    private val onFeedbackSpoken: (String) -> Unit
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _overlay = MutableStateFlow(DetectionOverlay(emptyList(), 1f))
    val overlay: StateFlow<DetectionOverlay> = _overlay.asStateFlow()

    @SuppressLint("ServiceCast")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var toneGenerator: ToneGenerator? = null

    private fun getToneGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            } catch (e: Exception) {
                Logger.e("BlindAidManager: Failed to create ToneGenerator", e)
            }
        }
        return toneGenerator
    }

    private val customModelFile = File(
        context.getExternalFilesDir("models"),
        "vision/blind-aid/custom_yolov8.tflite"
    )

    private val detector = if (customModelFile.exists()) {
        Logger.i("BlindAidManager: Custom Blind Aid model found at ${customModelFile.absolutePath}")
        val localModel = com.google.mlkit.common.model.LocalModel.Builder()
            .setAbsoluteFilePath(customModelFile.absolutePath)
            .build()
        val customOptions = CustomObjectDetectorOptions.Builder(localModel)
            .setDetectorMode(CustomObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .setMaxPerObjectLabelCount(3)
            .build()
        ObjectDetection.getClient(customOptions)
    } else {
        Logger.i("BlindAidManager: Custom model not installed; using offline ML Kit detector")
        val defaultOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(defaultOptions)
    }

    private var lastSpokenTime = 0L
    private var lastSpokenObject = ""

    // Eyes-free (WS3): periodic spoken scene summary ("In front of you: a chair, a desk, a
    // person"), throttled by BlindAidNarrator. Set quietMode=true to suppress scene narration
    // (close-obstacle warnings still fire). State is read/written only from the analyzer thread.
    @Volatile var quietMode: Boolean = false
    private var lastSceneNarrationTime = 0L
    private var lastSceneLabels: Set<String> = emptySet()

    fun getAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            private var frameCount = 0

            @SuppressLint("UnsafeOptInUsageError")
            override fun analyze(imageProxy: ImageProxy) {
                frameCount++
                if (frameCount % 6 != 0) {
                    imageProxy.close()
                    return
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val image = InputImage.fromMediaImage(mediaImage, rotation)
                    val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                    val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
                    detector.process(image)
                        .addOnSuccessListener { objects ->
                            if (objects.isEmpty()) {
                                _overlay.value = DetectionOverlay(emptyList(), uprightW.toFloat() / uprightH.toFloat())
                            } else {
                                processDetections(objects, uprightW, uprightH)
                            }
                        }
                        .addOnFailureListener { e ->
                            Logger.e("BlindAidManager: Real-time analysis failed", e)
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }
        }
    }

    private fun processDetections(objects: List<DetectedObject>, uprightW: Int, uprightH: Int) {
        if (objects.isEmpty()) return

        val aspectRatio = uprightW.toFloat() / uprightH.toFloat()
        val boxes = objects.map { obj ->
            val b = obj.boundingBox
            val label = obj.labels.firstOrNull()?.text ?: "Obstacle"
            DetectedBox(
                label = label,
                rect = RectF(
                    b.left.toFloat() / uprightW,
                    b.top.toFloat() / uprightH,
                    b.right.toFloat() / uprightW,
                    b.bottom.toFloat() / uprightH
                )
            )
        }
        _overlay.value = DetectionOverlay(boxes, aspectRatio)

        // Eyes-free (WS3): periodic spoken scene summary alongside the close-obstacle warnings
        // below. The throttle (BlindAidNarrator) absorbs label flicker and re-narrates a steady
        // scene at a longer interval so a blind user keeps a periodic sense of what's in front.
        val currentLabels = objects.mapNotNull { it.labels.firstOrNull()?.text }.toSet()
        val now = System.currentTimeMillis()
        if (BlindAidNarrator.shouldNarrateScene(
                nowMs = now,
                lastNarrationMs = lastSceneNarrationTime,
                lastLabels = lastSceneLabels,
                currentLabels = currentLabels,
                quietMode = quietMode
            )) {
            val summary = BlindAidNarrator.sceneSummary(currentLabels)
            if (summary.isNotBlank()) {
                lastSceneNarrationTime = now
                lastSceneLabels = BlindAidNarrator.normalize(currentLabels)
                onFeedbackSpoken(summary)
            }
        }

        var closestObject: DetectedObject? = null
        var maxArea = 0
        for (obj in objects) {
            val area = obj.boundingBox.width() * obj.boundingBox.height()
            if (area > maxArea) {
                maxArea = area
                closestObject = obj
            }
        }

        val target = closestObject ?: return
        val label = target.labels.firstOrNull()?.text ?: "Obstacle"
        val targetArea = target.boundingBox.width() * target.boundingBox.height()
        val screenArea = uprightW * uprightH
        val fillRatio = targetArea.toFloat() / screenArea

        if (fillRatio > 0.15f) {
            val beepDuration = if (fillRatio > 0.45f) {
                getToneGenerator()?.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
                100L
            } else {
                getToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                400L
            }

            val hapticIntensity = (fillRatio * 255).toInt().coerceIn(50, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(beepDuration, hapticIntensity))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(beepDuration)
            }

            val now = System.currentTimeMillis()
            if (now - lastSpokenTime > 3500 || lastSpokenObject != label) {
                lastSpokenTime = now
                lastSpokenObject = label
                onFeedbackSpoken(
                    if (fillRatio > 0.40f) "Stop! $label is directly in front of you."
                    else "$label ahead."
                )
            }
        }
    }

    fun release() {
        executor.shutdown()
        _overlay.value = DetectionOverlay(emptyList(), 1f)
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                Logger.w("BlindAidManager: Executor did not terminate in 2s, forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        try {
            detector.close()
        } catch (e: Exception) {
            Logger.e("BlindAidManager: Error closing detector", e)
        }

        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Logger.e("BlindAidManager: Error releasing ToneGenerator", e)
        }
        Logger.i("BlindAidManager: Released successfully")
    }
}
