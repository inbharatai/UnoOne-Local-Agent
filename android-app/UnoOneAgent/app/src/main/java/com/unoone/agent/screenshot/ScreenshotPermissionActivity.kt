package com.unoone.agent.screenshot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.unoone.agent.core.util.Logger
import com.unoone.agent.phonecontrol.ScreenshotCapture

/**
 * Transparent one-shot Activity that requests the MediaProjection screen-capture permission.
 *
 * It is started by [com.unoone.agent.execution.ActionExecutor] when a screenshot OCR fallback
 * is needed but no projection token is available. After the user grants or denies, the
 * activity stores the result in [ScreenshotCapture.mediaProjection] and finishes immediately.
 */
class ScreenshotPermissionActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                // Android 14+ requires the foreground service to be active before constructing the
                // MediaProjection. The service owns the token and reports readiness to the listener.
                MediaProjectionService.start(this, result.resultCode, result.data!!)
                Logger.i("ScreenshotPermissionActivity: projection consent forwarded to foreground service")
            } catch (t: Throwable) {
                Logger.e("ScreenshotPermissionActivity: failed to start projection service", t)
                val listener = ScreenshotCapture.permissionListener
                ScreenshotCapture.permissionListener = null
                listener?.invoke(false)
            }
        } else {
            Logger.w("ScreenshotPermissionActivity: MediaProjection denied")
            val listener = ScreenshotCapture.permissionListener
            ScreenshotCapture.permissionListener = null
            listener?.invoke(false)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Read Screen must capture the display the user is currently using. Android 14's
                // default consent UI prefers "A single app", which then opens an app picker and can
                // make UnoOne OCR an accidentally selected app instead of the current screen.
                // Pinning the request to the default display keeps the consent explicit while
                // removing that ambiguous second picker step.
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }
        projectionLauncher.launch(captureIntent)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ScreenshotPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
