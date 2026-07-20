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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.unoone.agent.core.agent.BlindAidNarrator
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.graphics.RectF
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** One detected object's bounding box in upright-image normalized coordinates. */
data class DetectedBox(val label: String, val rect: RectF)

/** A frame of Blind Aid detections plus the upright image aspect ratio. */
data class DetectionOverlay(val boxes: List<DetectedBox>, val aspectRatio: Float)

private data class BlindDetection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF
)

/**
 * Offline Blind Aid navigation system.
 *
 * This subsystem is deliberately independent of Gemma. It must continue detecting obstacles and
 * producing haptic, tone and spoken feedback when the LLM is absent, unloaded or recovering from
 * memory pressure. A custom detector may be installed under `models/vision/blind-aid/`; otherwise
 * the bundled offline EfficientDet-Lite0 detector is used.
 */
class BlindAidManager(
    private val context: Context,
    private val onFeedbackSpoken: (String) -> Unit,
    private val languageCodeProvider: () -> String = { "en" }
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var released = false

    /** Dedicated frame-analysis executor; CameraX/MediaPipe must never decode frames on the UI thread. */
    val analyzerExecutor: Executor get() = executor

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

    private val bundledDetectorAsset = "models/efficientdet_lite2_int8.tflite"

    // C2: the ObjectDetector is created LAZILY on the first analyzed frame, not at manager
    // construction. Eager construction blocks Blind Aid activation (native model load at composition
    // time) and, combined with the brain's RAM footprint, contributed to the slow/frozen activation.
    private var detector: ObjectDetector? = null
    private var detectorInitializationAttempted = false

    @Synchronized
    private fun getDetector(): ObjectDetector? {
        if (detector != null) return detector
        if (detectorInitializationAttempted) return null
        detectorInitializationAttempted = true
        detector = try {
            val baseOptions = if (customModelFile.exists()) {
                Logger.i("BlindAidManager: using user-installed detector at ${customModelFile.absolutePath}")
                val mappedModel = FileInputStream(customModelFile).use { stream ->
                    stream.channel.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY,
                        0L,
                        stream.channel.size()
                    )
                }
                BaseOptions.builder()
                    .setModelAssetBuffer(mappedModel)
                    .build()
            } else {
                // The stock ML Kit classifier only returns broad groups such as "home good".
                // EfficientDet-Lite0 carries COCO labels, so Blind Aid can say person, car,
                // bicycle, chair, dog, etc. while remaining fully offline.
                Logger.i("BlindAidManager: using bundled labeled EfficientDet-Lite2 detector")
                BaseOptions.builder()
                    .setModelAssetPath(bundledDetectorAsset)
                    .build()
            }
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                // Keep small COCO objects such as a cell phone available to the temporal filter.
                // Speech below uses a higher, class-aware threshold and consecutive-frame proof.
                .setScoreThreshold(0.24f)
                .build()
            ObjectDetector.createFromOptions(context, options)
        } catch (e: LinkageError) {
            // MediaPipe is a native/reflection-heavy dependency. A missing JNI symbol or an
            // incorrectly shrunk release must disable detection, never terminate the whole app.
            Logger.e("BlindAidManager: Object detector runtime unavailable", e)
            null
        } catch (e: Exception) {
            Logger.e("BlindAidManager: Failed to create object detector", e)
            null
        }
        return detector
    }

    private var lastSpokenTime = 0L
    private var lastSpokenObject = ""
    private var lastSpokenRiskBand = 0

    // Eyes-free (WS3): periodic spoken scene summary ("In front of you: a chair, a desk, a
    // person"), throttled by BlindAidNarrator. Set quietMode=true to suppress scene narration
    // (close-obstacle warnings still fire). State is read/written only from the analyzer thread.
    @Volatile var quietMode: Boolean = false
    private var lastSceneNarrationTime = 0L
    private var lastSceneLabels: Set<String> = emptySet()
    private var lastLoggedDetections: Set<String> = emptySet()
    private var lastDetectionLogTime = 0L
    private var lastNonEmptyDetectionTime = 0L
    private val labelConfirmationCounts = mutableMapOf<String, Int>()

    fun getAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            private var frameCount = 0

            @SuppressLint("UnsafeOptInUsageError")
            override fun analyze(imageProxy: ImageProxy) {
                if (released) {
                    imageProxy.close()
                    return
                }
                frameCount++
                // Lite0 runs comfortably on the Xiaomi's NPU/CPU; sampling every third camera
                // frame keeps the overlay near-real-time while KEEP_ONLY_LATEST prevents backlog.
                if (frameCount % 3 != 0) {
                    imageProxy.close()
                    return
                }

                try {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                    val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
                    val det = getDetector()
                    if (det == null) {
                        // C2: detector not available — drop this frame, keep the preview alive.
                        return
                    }
                    // CameraX supplies RGBA frames for this analyzer. Build a bitmap-backed MPImage:
                    // MediaPipe's Android MediaImage adapter does not accept YUV_420_888 frames.
                    val frameBitmap = imageProxy.toBitmap()
                    val mpImage = BitmapImageBuilder(frameBitmap).build()
                    var detailBitmap: android.graphics.Bitmap? = null
                    var detailImage: com.google.mediapipe.framework.image.MPImage? = null
                    try {
                        val processingOptions = ImageProcessingOptions.builder()
                            .setRotationDegrees(rotation)
                            .build()
                        val primary = det.detect(mpImage, processingOptions).detections().map { detection ->
                            val category = detection.categories().maxByOrNull { it.score() }
                            BlindDetection(
                                label = category?.categoryName().orEmpty().toReadableLabel(),
                                confidence = category?.score() ?: 0f,
                                boundingBox = detection.boundingBox()
                            )
                        }

                        // Small objects are easily lost when the entire 16:9 frame is resized to
                        // the detector's square input. A second centered 2/3 crop gives whatever the
                        // user points at 2.25x more detector area (especially useful for cell phones,
                        // remotes, labels and products) while the primary pass retains peripheral
                        // people/vehicle/obstacle coverage.
                        val cropLeft = frameBitmap.width / 6
                        val cropTop = frameBitmap.height / 6
                        val cropWidth = frameBitmap.width * 2 / 3
                        val cropHeight = frameBitmap.height * 2 / 3
                        val cropBitmap = android.graphics.Bitmap.createBitmap(
                            frameBitmap,
                            cropLeft,
                            cropTop,
                            cropWidth,
                            cropHeight
                        )
                        detailBitmap = cropBitmap
                        val cropImage = BitmapImageBuilder(cropBitmap).build()
                        detailImage = cropImage
                        val detailUprightW = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
                        val detailUprightH = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
                        val detailOffsetX = (uprightW - detailUprightW) / 2f
                        val detailOffsetY = (uprightH - detailUprightH) / 2f
                        val detail = det.detect(cropImage, processingOptions).detections().map { detection ->
                            val category = detection.categories().maxByOrNull { it.score() }
                            val box = detection.boundingBox()
                            BlindDetection(
                                label = category?.categoryName().orEmpty().toReadableLabel(),
                                confidence = category?.score() ?: 0f,
                                boundingBox = RectF(
                                    box.left + detailOffsetX,
                                    box.top + detailOffsetY,
                                    box.right + detailOffsetX,
                                    box.bottom + detailOffsetY
                                )
                            )
                        }
                        val objects = mergeDetections(primary, detail)
                        if (released) return
                        if (objects.isEmpty()) {
                            // A single blurred/transition frame must not make every box flicker
                            // off. Keep the last good scene briefly, then clear if it is genuinely
                            // gone. This is visual persistence only; no stale warning is spoken.
                            if (System.currentTimeMillis() - lastNonEmptyDetectionTime > 1_500L) {
                                labelConfirmationCounts.clear()
                                _overlay.value = DetectionOverlay(
                                    emptyList(),
                                    uprightW.toFloat() / uprightH.toFloat()
                                )
                            }
                        } else {
                            processDetections(objects, uprightW, uprightH)
                        }
                    } catch (e: Exception) {
                        Logger.e("BlindAidManager: Real-time analysis failed", e)
                    } finally {
                        detailImage?.close()
                        detailBitmap?.recycle()
                        mpImage.close()
                        frameBitmap.recycle()
                    }
                } catch (e: Exception) {
                    Logger.e("BlindAidManager: Frame conversion failed", e)
                } finally {
                    imageProxy.close()
                }
            }
        }
    }

    private fun processDetections(objects: List<BlindDetection>, uprightW: Int, uprightH: Int) {
        if (released || objects.isEmpty()) return
        lastNonEmptyDetectionTime = System.currentTimeMillis()

        val aspectRatio = uprightW.toFloat() / uprightH.toFloat()
        val boxes = objects.map { obj ->
            val b = obj.boundingBox
            DetectedBox(
                label = obj.label,
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
        val currentLabels = objects
            .filter { it.confidence >= speechScoreThreshold(it.label) }
            .map { it.label }
            .filterNot { it == "Obstacle" }
            .toSet()
        // Speech is stricter than the visual overlay: require the same reasonably confident label
        // in three consecutive analyzed frames. Brief guesses can still appear as exploratory boxes
        // but never become spoken facts.
        labelConfirmationCounts.keys.retainAll(currentLabels)
        currentLabels.forEach { label ->
            labelConfirmationCounts[label] = (labelConfirmationCounts[label] ?: 0) + 1
        }
        val confirmedLabels = labelConfirmationCounts
            .filterValues { it >= 3 }
            .keys
            .toSet()
        val diagnosticLabels = boxes.map { it.label }.toSet()
        val now = System.currentTimeMillis()
        // The generic fallback classifier can alternate between broad labels on adjacent frames.
        // Keep diagnostic logging useful without flooding logcat while the spoken narrator applies
        // its own, longer scene-stability throttle.
        if (diagnosticLabels != lastLoggedDetections && now - lastDetectionLogTime >= 3_500L) {
            lastLoggedDetections = diagnosticLabels
            lastDetectionLogTime = now
            Logger.i("BlindAidManager: detected ${boxes.size} object(s): ${diagnosticLabels.joinToString()}")
        }
        if (BlindAidNarrator.shouldNarrateScene(
                nowMs = now,
                lastNarrationMs = lastSceneNarrationTime,
                lastLabels = lastSceneLabels,
                currentLabels = confirmedLabels,
                // Do not suppress names merely because an object is close. The closest-object
                // label can flicker and fail the separate obstacle-warning confirmation; this
                // stable summary is the guaranteed eyes-free path for identified objects.
                quietMode = quietMode
            )) {
            val summary = BlindAidNarrator.sceneSummary(
                confirmedLabels,
                languageCodeProvider()
            )
            if (summary.isNotBlank()) {
                lastSceneNarrationTime = now
                lastSceneLabels = BlindAidNarrator.normalize(confirmedLabels)
                if (!released) {
                    Logger.i("BlindAidManager: speaking fresh object labels=${lastSceneLabels.joinToString()}")
                    onFeedbackSpoken(summary)
                }
            }
        }

        var closestObject: BlindDetection? = null
        var maxArea = 0f
        for (obj in objects) {
            val area = obj.boundingBox.width() * obj.boundingBox.height()
            if (area > maxArea) {
                maxArea = area
                closestObject = obj
            }
        }

        val target = closestObject ?: return
        val label = target.label
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
            val riskBand = if (fillRatio > 0.40f) 2 else 1
            val isFreshConfirmedLabel = label in confirmedLabels
            val firstWarning = lastSpokenTime == 0L
            val escalated = riskBand > lastSpokenRiskBand && now - lastSpokenTime >= 2_000L
            // Detector label flicker is not a reason to chatter. A changed label must remain useful
            // long enough to clear this cooldown; an unchanged object is only a 30-second reminder.
            val changedAfterCooldown = lastSpokenObject != label && now - lastSpokenTime >= 15_000L
            val reminderDue = now - lastSpokenTime >= 30_000L
            if (isFreshConfirmedLabel && (firstWarning || escalated || changedAfterCooldown || reminderDue)) {
                lastSpokenTime = now
                lastSpokenObject = label
                lastSpokenRiskBand = riskBand
                if (!released) {
                    onFeedbackSpoken(
                        BlindAidNarrator.proximityWarning(
                            label = label,
                            immediate = fillRatio > 0.40f,
                            languageCode = languageCodeProvider()
                        )
                    )
                }
            }
        }
    }

    private fun String.toReadableLabel(): String {
        return replace('_', ' ').trim().ifEmpty { "Obstacle" }
    }

    private fun mergeDetections(
        primary: List<BlindDetection>,
        detail: List<BlindDetection>
    ): List<BlindDetection> {
        val merged = primary.toMutableList()
        detail.forEach { candidate ->
            val duplicate = merged.indexOfFirst {
                it.label.equals(candidate.label, ignoreCase = true) &&
                    intersectionOverUnion(it.boundingBox, candidate.boundingBox) >= 0.35f
            }
            if (duplicate < 0) merged += candidate
            else if (candidate.confidence > merged[duplicate].confidence) merged[duplicate] = candidate
        }
        return merged.sortedByDescending { it.confidence }.take(10)
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private companion object {
        /** Visual boxes may be exploratory; spoken labels need materially stronger evidence. */
        const val SPEECH_SCORE_THRESHOLD = 0.42f
        const val SMALL_OBJECT_SPEECH_SCORE_THRESHOLD = 0.30f
    }

    private fun speechScoreThreshold(label: String): Float =
        if (label.equals("cell phone", ignoreCase = true)) SMALL_OBJECT_SPEECH_SCORE_THRESHOLD
        else SPEECH_SCORE_THRESHOLD

    fun release() {
        released = true
        // Blind Aid scene state is intentionally session-only. Closing the panel must behave like
        // a hard cache boundary: no old TV/person label, confirmation, reminder timestamp, or box
        // can leak into the next activation.
        labelConfirmationCounts.clear()
        lastSpokenObject = ""
        lastSpokenRiskBand = 0
        lastSpokenTime = 0L
        lastSceneLabels = emptySet()
        lastSceneNarrationTime = 0L
        lastLoggedDetections = emptySet()
        lastDetectionLogTime = 0L
        lastNonEmptyDetectionTime = 0L
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
            detector?.close()
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
