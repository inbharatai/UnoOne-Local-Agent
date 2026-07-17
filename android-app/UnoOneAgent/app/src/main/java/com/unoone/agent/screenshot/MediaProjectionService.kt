package com.unoone.agent.screenshot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.unoone.agent.R
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.phonecontrol.ScreenshotCapture

/** Owns the MediaProjection token for OCR as required by Android 14+ foreground-service rules. */
class MediaProjectionService : Service() {
    private var projection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen reading", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while UnoOne can read the screen with on-device OCR"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AgentRuntimeGate.isEnabled()) {
            notifyPermission(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("UnoOne screen reading active")
            .setContentText("Screen content stays on this device and is processed with on-device OCR.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyPermission(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            projection?.stop()
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val granted = manager.getMediaProjection(resultCode, resultData)
            projection = granted
            granted.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ScreenshotCapture.clearProjection(granted)
                    if (projection === granted) projection = null
                    stopSelf()
                }
            }, null)
            ScreenshotCapture.installProjection(granted)
            Logger.i("MediaProjectionService: projection granted under foreground service")
            notifyPermission(true)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Logger.e("MediaProjectionService: unable to start projection", t)
            notifyPermission(false)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        val active = projection
        projection = null
        if (active != null) {
            ScreenshotCapture.clearProjection(active)
            runCatching { active.stop() }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notifyPermission(granted: Boolean) {
        val listener = ScreenshotCapture.permissionListener
        ScreenshotCapture.permissionListener = null
        listener?.invoke(granted)
    }

    companion object {
        private const val CHANNEL_ID = "screen_reading_channel"
        private const val NOTIFICATION_ID = 2003
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            if (!AgentRuntimeGate.isEnabled()) return
            val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
