package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 4E: ViewModel for Privacy Settings.
 * Uses EncryptedSharedPreferences for secure storage of privacy preferences.
 * All settings default to OFF (privacy-first).
 */
class PrivacySettingsViewModel(context: Context) : ViewModel() {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "unoone_privacy_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _bhashiniTtsEnabled = MutableStateFlow(prefs.getBoolean(KEY_BHASHINI_TTS, false))
    val bhashiniTtsEnabled: StateFlow<Boolean> = _bhashiniTtsEnabled.asStateFlow()

    private val _onlineRagEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_RAG, false))
    val onlineRagEnabled: StateFlow<Boolean> = _onlineRagEnabled.asStateFlow()

    private val _onlineSttFallbackEnabled = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_STT_FALLBACK, false))
    val onlineSttFallbackEnabled: StateFlow<Boolean> = _onlineSttFallbackEnabled.asStateFlow()

    private val _analyticsSharingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ANALYTICS_SHARING, false))
    val analyticsSharingEnabled: StateFlow<Boolean> = _analyticsSharingEnabled.asStateFlow()

    fun setBhashiniTtsEnabled(enabled: Boolean) {
        _bhashiniTtsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BHASHINI_TTS, enabled).apply()
    }

    fun setOnlineRagEnabled(enabled: Boolean) {
        _onlineRagEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ONLINE_RAG, enabled).apply()
    }

    fun setOnlineSttFallbackEnabled(enabled: Boolean) {
        _onlineSttFallbackEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ONLINE_STT_FALLBACK, enabled).apply()
    }

    fun setAnalyticsSharingEnabled(enabled: Boolean) {
        _analyticsSharingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ANALYTICS_SHARING, enabled).apply()
    }

    companion object {
        private const val KEY_BHASHINI_TTS = "bhashini_tts_enabled"
        private const val KEY_ONLINE_RAG = "online_rag_enabled"
        private const val KEY_ONLINE_STT_FALLBACK = "online_stt_fallback_enabled"
        private const val KEY_ANALYTICS_SHARING = "analytics_sharing_enabled"
    }
}