package com.unoone.agent.overlay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.unoone.agent.TaskStopReceiver

/** Native notification admission only, never an overlay/window whitelist or compositor proof.
 * No foreground Stop alternative is currently registered: absent notification means deny hiding.
 */
object StopSurfaceAdmission {
    fun nativeStopNotificationAvailable(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val stop = TaskStopReceiver.pendingIntent(context)
        manager.activeNotifications.any { entry ->
            val notification = entry.notification
            val channel = manager.getNotificationChannel(notification.channelId)
            entry.packageName == context.packageName && channel != null &&
                channel.importance != NotificationManager.IMPORTANCE_NONE &&
                (channel.group == null || manager.getNotificationChannelGroup(channel.group)?.isBlocked != true) &&
                notification.actions?.any { it.actionIntent == stop } == true
        }
    }.getOrDefault(false)
}
