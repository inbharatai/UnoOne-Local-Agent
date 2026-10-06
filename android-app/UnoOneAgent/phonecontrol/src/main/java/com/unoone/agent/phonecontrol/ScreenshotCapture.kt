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
import android.os.SystemClock
import com.unoone.agent.core.device.RectData
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
data class CapturedScreen(val bitmap: Bitmap, val requestedAtNanos: Long, val capturedAtNanos: Long,
    val displayBounds: RectData, val rotation: Int, val captureSequence: Long) {
    val capturedAtMs: Long get() = capturedAtNanos / 1_000_000
}

class ScreenshotCapture(context: Context) {
    private val context = context.applicationContext

    companion object {
        const val REQUEST_CODE = 9001

        /** Holds the granted MediaProjection across the app session. */
        @JvmStatic
        @Volatile
        var mediaProjection: MediaProjection? = null
            private set

        private val authority = com.unoone.agent.core.overlay.ProjectionFrameAuthority<MediaProjection> { com.unoone.agent.core.runtime.GlobalTaskCancellation.generation }

        private var sharedImageReader: ImageReader? = null
        private var sharedVirtualDisplay: VirtualDisplay? = null
        private var sequence: Long = 0
        private var sharedWidth: Int = 0
        private var sharedHeight: Int = 0

        /** Installs a projection only after the app's media-projection foreground service starts. */
        @JvmStatic
        fun installProjection(projection: MediaProjection) {
            val ticket = authority.install(projection) // revoke old frames BEFORE waiting for IO
            synchronized(this) {
                if (authority.snapshot() !== ticket) return
                releaseCaptureSession()
                mediaProjection = projection
            }
        }

        /** Clears only the currently installed token, ignoring stale service callbacks. */
        @JvmStatic
        fun clearProjection(projection: MediaProjection? = null) {
            val owner = projection ?: authority.snapshot()?.owner ?: return
            if (!authority.clear(owner)) return
            synchronized(this) {
                if (mediaProjection !== owner) return
                releaseCaptureSession()
                mediaProjection = null
            }
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
        fun hasPermission(): Boolean = authority.snapshot()?.let { authority.current(it) } == true
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
    fun captureScreen(): Result<Bitmap> = when (val frame = captureFrame()) {
        is Result.Success -> Result.Success(frame.data.bitmap)
        is Result.Error -> Result.Error(frame.message)
    }

    @Suppress("DEPRECATION")
    fun captureFrame(): Result<CapturedScreen> {
        val ticket = authority.snapshot() ?: return Result.Error("Screen capture permission not granted")
        return synchronized(Companion) {
        if (!authority.current(ticket) || mediaProjection !== ticket.owner)
            return@synchronized Result.Error("Screen capture authority revoked")
        val projection = ticket.owner

        val metrics = getDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            // Android 14+ allows one createVirtualDisplay() call per MediaProjection grant. Keep
            // the display/reader alive and reuse it for subsequent voice "read screen" requests.
            if (sharedVirtualDisplay == null || sharedImageReader == null ||
                sharedWidth != width || sharedHeight != height
            ) {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val oldReader = sharedImageReader
                val existing = sharedVirtualDisplay
                try {
                    if (existing != null) {
                        existing.resize(width, height, density)
                        existing.surface = reader.surface
                    } else sharedVirtualDisplay = projection.createVirtualDisplay(
                        "UnoOneScreenshot",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        Handler(Looper.getMainLooper())
                    )
                    sharedImageReader = reader
                    sharedWidth = width
                    sharedHeight = height
                    oldReader?.close()
                } catch (e: Exception) { reader.close(); throw e }
            }
            val imageReader = sharedImageReader
                ?: return@synchronized Result.Error("Screen capture session unavailable")

            // Drain every queued pre-request buffer. Timestamp, not acquisition time, proves freshness.
            val drainDeadline = SystemClock.elapsedRealtimeNanos() + 100_000_000L
            while (true) {
                if (SystemClock.elapsedRealtimeNanos() >= drainDeadline)
                    return@synchronized Result.Error("Screenshot queue did not drain")
                val old = imageReader.acquireNextImage() ?: break
                old.close()
            }
            val fence = SystemClock.elapsedRealtimeNanos()
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val deadline = fence + 750_000_000L
            var image: android.media.Image? = null
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                if (!authority.current(ticket)) return@synchronized Result.Error("Screen capture authority revoked")
                val candidate = imageReader.acquireLatestImage()
                if (candidate != null) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (candidate.timestamp > fence && candidate.timestamp <= now) { image = candidate; break }
                    candidate.close()
                }
                Thread.sleep(10)
            }
            val fresh = image ?: return@synchronized Result.Error("No post-request screenshot frame")
            if ((context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation != rotation) {
                fresh.close()
                return@synchronized Result.Error("Display rotated during capture")
            }

            try {
                val planes = fresh.planes
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
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped !== bitmap) bitmap.recycle()
                val accepted = authority.accept(ticket, cropped) { it.recycle() }
                    ?: return@synchronized Result.Error("Screen capture authority revoked")
                Result.Success(CapturedScreen(accepted, fence, fresh.timestamp, RectData(0, 0, width, height), rotation, ++sequence))
            } finally { fresh.close() }
        } catch (e: Exception) {
            Logger.e("ScreenshotCapture: capture failed", e)
            Result.Error("Screenshot capture failed: ${e.message}")
        }
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
                densityDpi = context.resources.displayMetrics.densityDpi
            }
        } else {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            displayMetrics
        }
    }
}
