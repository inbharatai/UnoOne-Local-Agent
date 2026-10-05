package com.unoone.agent.storage

import android.content.Context

/** Persisted user model choice. Absence means the one-time legacy selection migration is pending. */
class PreferencesManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("unoone_settings", Context.MODE_PRIVATE)

    val selectedBrainManifestId: String?
        get() = preferences.getString(KEY_SELECTED_BRAIN, null)

    /** Call off the main thread; commit before exposing the new runtime selection. */
    fun setSelectedBrainManifestId(manifestId: String) {
        check(preferences.edit().putString(KEY_SELECTED_BRAIN, manifestId).commit()) {
            "Could not persist brain selection"
        }
    }

    companion object {
        private const val KEY_SELECTED_BRAIN = "selected_brain_manifest_id"
    }
}
