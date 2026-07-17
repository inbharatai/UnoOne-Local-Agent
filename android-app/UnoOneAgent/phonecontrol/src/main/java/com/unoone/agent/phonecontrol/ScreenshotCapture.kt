package com.unoone.agent.phonecontrol

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowMetrics
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger

/**
 * Helper for capturing the device screen via MediaProjection.
 *
 * - Permission is obtained once through [requestPermission]/[onActivityResult] or a dedicated
 *   transparent activity in the app module.
 * - After permission is granted, [captureScreen] creates an [ImageReader], renders a frame,
 *   and returns a [Bitmap] for OCR.
 */
class ScreenshotCapture(private val context: Context) {

    companion object {
        const val REQUEST_CODE = 9001

        /** Holds the granted MediaProjection across the app session. */
        @JvmStatic
        @Volatile
        var mediaProjection: MediaProjection? = null
            private set

        private var sharedImageReader: ImageReader? = null
        private var sharedVirtualDisplay: VirtualDisplay? = null
        private var sharedWidth: Int = 0
        private var sharedHeight: Int = 0

        /** Installs a projection only after the app's media-projection foreground service starts. */
        @JvmStatic
        @Synchronized
        fun installProjection(projection: MediaProjection) {
            releaseCaptureSession()
            mediaProjection = projection
        }

        /** Clears only the currently installed token, ignoring stale service callbacks. */
        @JvmStatic
        @Synchronized
        fun clearProjection(projection: MediaProjection? = null) {
            if (projection != null && mediaProjection !== projection) return
            releaseCaptureSession()
            mediaProjection = null
        }

        @Synchronized
        private fun releaseCaptureSession() {
            runCatching { sharedVirtualDisplay?.release() }
            runCatching { sharedImageReader?.close() }
            sharedVirtualDisplay = null
            sharedImageReader = null
            sharedWidth = 0
            sharedHeight = 0
        }

        /** Optional listener invoked when the permission activity finishes. */
        @JvmStatic
        var permissionListener: ((granted: Boolean) -> Unit)? = null

        @JvmStatic
        fun hasPermission(): Boolean = mediaProjection != null
    }

    /**
     * Launch the system screen-capture permission intent from an [Activity].
     * The caller must forward [onActivityResult] to this class.
     */
    fun requestPermission(activity: Activity) {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    /**
     * To be called from the host Activity's [Activity.onActivityResult].
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            installProjection(manager.getMediaProjection(resultCode, data))
            Logger.i("ScreenshotCapture: MediaProjection permission granted")
            permissionListener?.invoke(true)
        } else {
            Logger.w("ScreenshotCapture: MediaProjection permission denied")
            permissionListener?.invoke(false)
        }
    }

    /**
     * Capture the current screen into a [Bitmap]. Requires [mediaProjection] to be non-null.
     */
    @Suppress("DEPRECATION")
    @Synchronized
    fun captureScreen(): Result<Bitmap> {
        val projection = mediaProjection
            ?: return Result.Error("Screen capture permission not granted")

        val metrics = getDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        return try {
            // Android 14+ allows one createVirtualDisplay() call per MediaProjection grant. Keep
            // the display/reader alive and reuse it for subsequent voice "read screen" requests.
            if (sharedVirtualDisplay == null || sharedImageReader == null ||
                sharedWidth != width || sharedHeight != height
            ) {
                releaseCaptureSession()
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                sharedImageReader = reader
                sharedWidth = width
                sharedHeight = height
                sharedVirtualDisplay = projection.createVirtualDisplay(
                    "UnoOneScreenshot",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    Handler(Looper.getMainLooper())
                )
            }
            val imageReader = sharedImageReader
                ?: return Result.Error("Screen capture session unavailable")

            // Wait briefly for a frame to be available.
            var image = imageReader.acquireLatestImage()
            var retries = 10
            while (image == null && retries > 0) {
                Thread.sleep(50)
                image = imageReader.acquireLatestImage()
                retries--
            }

            if (image == null) {
                return Result.Error("Could not acquire screenshot frame")
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            Result.Success(bitmap)
        } catch (e: Exception) {
            Logger.e("ScreenshotCapture: capture failed", e)
            Result.Error("Screenshot capture failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(): DisplayMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = DisplayMetrics.DENSITY_MEDIUM
            }
        } else {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            displayMetrics
        }
    }
}
