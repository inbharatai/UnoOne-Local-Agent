package com.unoone.agent.owl

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.unoone.agent.core.device.RectData
import com.unoone.agent.core.model.Result
import com.unoone.agent.phonecontrol.ScreenshotCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Fixtures may provide encoded bytes; such unit fixtures are NOT native vision evidence. */
interface OwlCapturePort {
    fun hasPermission(): Boolean
    suspend fun capture(): OwlCapturedImage?
}
data class OwlCapturedImage(val bytes: ByteArray, val width: Int, val height: Int,
    val displayBounds: RectData, val rotation: Int, val capturedAtMs: Long)

class AndroidOwlCapturePort(private val context: Context,
    private val frameSource: suspend () -> Result<com.unoone.agent.phonecontrol.CapturedScreen> = { ScreenshotCapture(context).captureFrame() }
) : OwlCapturePort {
    override fun hasPermission() = ScreenshotCapture.hasPermission()
    override suspend fun capture(): OwlCapturedImage? = withContext(Dispatchers.IO) {
        val frame = frameSource()
        if (frame !is Result.Success) return@withContext null
        val capture = frame.data
        val bitmap = capture.bitmap
        try {
            check(bitmap.width == capture.displayBounds.right - capture.displayBounds.left &&
                bitmap.height == capture.displayBounds.bottom - capture.displayBounds.top)
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val divisor = gcd(bitmap.width, bitmap.height)
            val unitW = bitmap.width / divisor; val unitH = bitmap.height / divisor
            val factor = 512 / maxOf(unitW, unitH)
            if (factor < 1) return@withContext null
            val resized = bitmap.scale(unitW * factor, unitH * factor)
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    check(resized.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                }
                OwlCapturedImage(bytes, resized.width, resized.height, capture.displayBounds, capture.rotation, capture.capturedAtMs)
            } finally { if (resized !== bitmap) resized.recycle() }
        } finally { bitmap.recycle() }
    }
}
