package com.unoone.agent.phonecontrol

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression gate for the small-object miss that was reproduced with a physical handset. */
@RunWith(AndroidJUnit4::class)
class BlindAidCellPhoneModelTest {

    @Test
    fun lite2DetectsCellPhoneInRepresentativePhoto() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open("fixtures/smartphone_use_cc0.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("models/efficientdet_lite2_int8.tflite")
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(10)
            .setScoreThreshold(0.24f)
            .build()

        ObjectDetector.createFromOptions(targetContext, options).use { detector ->
            val centerCrop = Bitmap.createBitmap(
                bitmap,
                bitmap.width / 6,
                bitmap.height / 6,
                bitmap.width * 2 / 3,
                bitmap.height * 2 / 3
            )
            val image = BitmapImageBuilder(bitmap).build()
            val detailImage = BitmapImageBuilder(centerCrop).build()
            try {
                val labels = (detector.detect(image).detections() + detector.detect(detailImage).detections())
                    .flatMap { it.categories() }
                    .map { it.categoryName().lowercase() to it.score() }
                assertTrue("Expected cell phone; detector returned $labels", labels.any { it.first == "cell phone" })
            } finally {
                image.close()
                detailImage.close()
                centerCrop.recycle()
                bitmap.recycle()
            }
        }
    }
}
