package com.unoone.agent.safety

import android.content.Context
import androidx.core.content.edit

/**
 * User-selectable safety posture for the agent, exposed as an in-app setting (see Settings →
 * Security Level). This is the single source of truth that [com.unoone.agent.AgentOrchestrator]
 * consults at every validated tool call to decide how much friction / hard-blocking to apply.
 *
 * The three levels trade safety friction against demo-ability:
 *
 * - [STANDARD] — the production default. The Gemma safety judge runs (catches paraphrased harm),
 *   the keyword BLOCK tier is enforced (payments, credentials, install, silent control), and every
 *   CONFIRM / STRONG_CONFIRM action requires an explicit user tap. Nothing is auto-run.
 * - [RELAXED] — for everyday testing. The judge is disabled (so it can no longer hard-block benign
 *   commands like "add a calendar event" via a false-positive UNSAFE verdict), and CONFIRM /
 *   STRONG_CONFIRM actions auto-run without a tap. The hard BLOCK tier STAYS enforced, so real
 *   money / credential / install actions are still refused. This removes the over-cautious
 *   friction the user hit (calendar block) while keeping the genuinely dangerous rails.
 * - [OFF] — the full demo / developer mode. Judge off, BLOCK tier off, confirmations auto-approved.
 *   Every module can be exercised. This is safe only because the BLOCK-tier tool names
 *   (make_payment / send_message / access_passwords / install_app / silent_control) have **no
 *   executor handlers** — they fall through to the plugin router (a no-op error) — so unblocking
 *   them triggers no real payment / SMS / credential / install side effect. There is nothing
 *   dangerous behind those names; OFF only removes the rejection message.
 *
 * Persisted in the shared `unoone_settings` store so [SettingsViewModel] and the orchestrator read
 * the same value. The orchestrator re-reads per tool call, so a level change takes effect on the
 * next command without an app restart.
 */
enum class SecurityLevel {
    STANDARD,
    RELAXED,
    OFF;

    companion object {
        const val PREF_NAME = "unoone_settings"
        const val PREF_KEY = "security_level"

        /** Current level from prefs, defaulting to [STANDARD] (never silently weaken safety). */
        fun current(context: Context): SecurityLevel {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREF_KEY, STANDARD.name)
            return entries.firstOrNull { it.name == raw } ?: STANDARD
        }

        /** Persist [level]. Called by [com.unoone.agent.ui.viewmodel.SettingsViewModel]. */
        fun set(context: Context, level: SecurityLevel) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit { putString(PREF_KEY, level.name) }
        }
    }
}