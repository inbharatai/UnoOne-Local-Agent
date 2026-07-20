package com.unoone.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unoone.agent.core.util.Logger

/**
 * ADB-only physical-device hook for debug builds.
 *
 * The receiver requires Android's signature-level DUMP permission, which the shell test identity
 * owns but ordinary installed applications do not. It never exists in release builds and never
 * logs command content. This makes the full post-STT agent path repeatable on Xiaomi devices where
 * shell input injection and a separate instrumentation APK are blocked by device policy.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? UnoOneApplication ?: return
        when (intent.action) {
            ACTION_COMMAND -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
                if (command.isNotBlank()) {
                    Logger.i("DebugCommandReceiver: routing private test command")
                    app.postVoiceCommand(command)
                }
            }
            ACTION_ENABLE -> app.enableAgent()
            ACTION_DISABLE -> app.disableAgent()
        }
    }

    companion object {
        const val ACTION_COMMAND = "com.unoone.agent.debug.COMMAND"
        const val ACTION_ENABLE = "com.unoone.agent.debug.ENABLE"
        const val ACTION_DISABLE = "com.unoone.agent.debug.DISABLE"
        const val EXTRA_COMMAND = "command"
    }
}
