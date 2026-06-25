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
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.RectF
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One detected object's bounding box, normalized to [0,1] in the **upright** image's coordinate
 * space (left/top/right/bottom). Upright = the image after [ImageProxy.imageInfo.rotationDegrees]
 * is applied, which is what ML Kit returns boxes in and what the preview displays. Consumed by
 * [com.unoone.agent.ui.screens.BlindAidCameraPreview] to draw a Compose overlay.
 */
data class DetectedBox(val label: String, val rect: RectF)

/**
 * A frame's worth of detections for the overlay: the boxes plus the upright image aspect ratio
 * (width/height) the boxes are normalized against, so the overlay can apply FILL_CENTER mapping.
 */
data class DetectionOverlay(val boxes: List<DetectedBox>, val aspectRatio: Float)

/**
 * World-Class Blind Aid Navigation System.
 * Combines CameraX continuous frames (3-5 fps), custom YOLOv8/MobileNet TFLite models,
 * haptic vibration pulses, and audio beep frequency (Car Parking Sensor style)
 * to guide visually impaired users offline and hands-free.
 */
class BlindAidManager(
    private val context: Context,
    private val onFeedbackSpoken: (String) -> Unit
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Live detection overlay state published from the analyzer thread. Collected by
    // BlindAidCameraPreview to draw bounding boxes over the camera preview. StateFlow is
    // thread-safe; updates at ~5 FPS (the analyzer throttle) drive Compose recomposition.
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

    // 0C-5: ToneGenerator can throw on some devices — create lazily with try-catch
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

    // Option C: Loader for Custom YOLOv8-Nano / MobileNet 4-bit TFLite model
    private val customModelFile = File(context.getExternalFilesDir("models"), "gemma-local/custom_yolov8.tflite")
    
    private val detector = if (customModelFile.exists()) {
        Logger.i("BlindAidManager: Custom YOLOv8 TFLite model found. Initializing custom detector.")
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
        Logger.i("BlindAidManager: Custom model not found, defaulting to high-accuracy offline ML Kit detector.")
        val defaultOptions = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(defaultOptions)
    }

    private var lastSpokenTime = 0L
    private var lastSpokenObject = ""

    // Option A: CameraX ImageAnalysis.Analyzer running continuously (3 to 5 frames per second)
    fun getAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            private var frameCount = 0

            @SuppressLint("UnsafeOptInUsageError")
            override fun analyze(imageProxy: ImageProxy) {
                frameCount++
                // Throttle: Only process 1 out of 6 frames (approx 5 fps on a 30fps stream)
                if (frameCount % 6 != 0) {
                    imageProxy.close()
                    return
                }

                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val image = InputImage.fromMediaImage(mediaImage, rotation)
                    // ML Kit returns boxes in the upright (rotated) image's coordinate space.
                    // For 90°/270° rotations the upright dimensions swap vs. the raw sensor dims.
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
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }
        }
    }

    /**
     * Option B: Processes local detections to generate real-time haptic & audio beeping feedback,
     * and publishes the full set of bounding boxes to [overlay] for the Compose camera preview.
     */
    private fun processDetections(objects: List<DetectedObject>, uprightW: Int, uprightH: Int) {
        if (objects.isEmpty()) return

        // Publish normalized boxes for every detected object so the overlay shows them all.
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

        var closestObject: DetectedObject? = null
        var maxArea = 0

        for (obj in objects) {
            val bounds = obj.boundingBox
            val area = bounds.width() * bounds.height()
            if (area > maxArea) {
                maxArea = area
                closestObject = obj
            }
        }

        val target = closestObject ?: return
        val label = target.labels.firstOrNull()?.text ?: "Obstacle"
        val bounds = target.boundingBox
        val targetArea = bounds.width() * bounds.height()

        // Bounding box size as a proxy for distance (larger box = closer to camera)
        val screenArea = uprightW * uprightH
        val fillRatio = targetArea.toFloat() / screenArea

        // Calculate feedback dynamic frequency based on proximity
        if (fillRatio > 0.15f) {
            
            // Audio Cue: Car Parking Sensor style beeping
            val beepDuration = if (fillRatio > 0.45f) {
                // Immediate danger: Solid tone / Continuous beep
                getToneGenerator()?.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
                100L
            } else {
                // Warning zone: Pulsing tone
                getToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                400L
            }

            // Haptic Cue: Vibrations increase in intensity and frequency
            val hapticIntensity = (fillRatio * 255).toInt().coerceIn(50, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(beepDuration, hapticIntensity))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(beepDuration)
            }

            // Speech Guidance: Speaks object categories with throttling to avoid chatters
            val now = System.currentTimeMillis()
            if (now - lastSpokenTime > 3500 || lastSpokenObject != label) {
                lastSpokenTime = now
                lastSpokenObject = label
                val speechInstruction = if (fillRatio > 0.40f) {
                    "Stop! $label is directly in front of you."
                } else {
                    "$label ahead."
                }
                onFeedbackSpoken(speechInstruction)
            }
        }
    }

    fun release() {
        // 0C-5: Proper executor shutdown with awaitTermination
        executor.shutdown()
        // Clear any stale boxes so a re-activation doesn't briefly show the last frame.
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

        // 0C-2: Close ML Kit detector to prevent leak
        try {
            detector.close()
        } catch (e: Exception) {
            Logger.e("BlindAidManager: Error closing detector", e)
        }

        // 0C-5: Release ToneGenerator
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Logger.e("BlindAidManager: Error releasing ToneGenerator", e)
        }
        Logger.i("BlindAidManager: Released successfully")
    }
}
