package com.unoone.agent.voice

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.unoone.agent.core.latency.*

object VoiceLatency {
    val recorder = LatencyRecorder(LatencyClock { android.os.SystemClock.elapsedRealtimeNanos() })
}
enum class ListeningCue { SPOKEN, VISUAL_HAPTIC }
object ListeningCuePolicy {
    fun selected(preference: ListeningCue, touchExploration: Boolean): ListeningCue =
        if (touchExploration) ListeningCue.SPOKEN else preference
    fun preference(context: Context): ListeningCue = runCatching {
        ListeningCue.valueOf(context.getSharedPreferences("voice_cue", Context.MODE_PRIVATE).getString("mode", ListeningCue.SPOKEN.name)!!)
    }.getOrDefault(ListeningCue.SPOKEN)
    fun setPreference(context: Context, cue: ListeningCue) {
        context.getSharedPreferences("voice_cue", Context.MODE_PRIVATE).edit().putString("mode", cue.name).apply()
    }
    /** Selection only: caller must await existing speech and signal readiness only after recorder success. */
    fun selected(context: Context, onSelected: (ListeningCue) -> Unit = {}): ListeningCue {
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return selected(preference(context), accessibility?.isTouchExplorationEnabled == true).also(onSelected)
    }
}
