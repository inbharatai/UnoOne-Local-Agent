package com.unoone.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.screenshot.MediaProjectionService

/** Explicit immutable capability; not exported and no intent filter. Works with master disabled. */
class TaskStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        GlobalTaskCancellation.cancelAll()
        val projectionService = Intent(context, MediaProjectionService::class.java)
        context.stopService(projectionService)
    }
    companion object {
        const val ACTION = "com.unoone.agent.STOP_ALL_TASKS"
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 701,
            Intent(context, TaskStopReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
