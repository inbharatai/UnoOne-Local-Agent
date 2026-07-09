package com.unoone.agent.brain

import android.content.Context
import androidx.core.content.edit
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.BrainModelSpec

/**
 * Persists the user's chosen brain model profile (by manifest id) in the shared `unoone_settings`
 * store. The default remains the device-verified fallback (Gemma 3n E4B) until the Gemma 4 device
 * matrix in `DEVICE_VERIFICATION.md` passes — changing the default is a device-verification decision,
 * not a code decision.
 *
 * Reads tolerate a blank/unknown/legacy ("gemma-local") persisted value by resolving to the default
 * profile via [BrainModelRegistry.resolveOrDefault], so an upgrade never breaks the agent.
 */
object BrainSelection {

    private const val PREFS = "unoone_settings"
    private const val KEY = "brain_model_manifest_id"

    /** The currently selected brain profile (resolved from the persisted id, or the default). */
    fun selected(context: Context): BrainModelSpec =
        BrainModelRegistry.resolveOrDefault(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    /** The manifest id of the currently selected profile. */
    fun manifestId(context: Context): String = selected(context).manifestId

    /**
     * Persist a selection. The id is normalized through [BrainModelRegistry.resolveOrDefault] so a
     * bogus value can never be saved — it falls back to the default profile instead.
     */
    fun set(context: Context, manifestId: String) {
        val resolved = BrainModelRegistry.resolveOrDefault(manifestId).manifestId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, resolved) }
    }
}