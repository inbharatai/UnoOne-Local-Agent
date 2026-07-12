package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode

/**
 * Temporary compatibility mapping for the speech models already supported by UnoOne.
 *
 * The next migration phase replaces this hard-coded catalogue with signed downloadable language
 * packs. Until then, every path here must match `models_manifest.json` exactly so the normalized
 * model filesystem does not break the existing offline voice runtime.
 */
data class AsrSpec(val folder: String, val mode: SttMode, val whisperLanguage: String)

object VoiceLanguage {

    const val PREF_NAME = "unoone_settings"
    const val PREF_KEY = "voice_language"
    const val DEFAULT = "en"

    data class Lang(val code: String, val display: String)

    val SUPPORTED: List<Lang> = listOf(
        Lang("en", "English"),
        Lang("hi", "Hindi"),
        Lang("bn", "Bengali"),
        Lang("ta", "Tamil"),
        Lang("te", "Telugu"),
        Lang("kn", "Kannada"),
        Lang("ml", "Malayalam")
    )

    private val ttsFolderByCode: Map<String, String> = mapOf(
        "en" to "speech/languages/en-IN/tts",
        "hi" to "speech/languages/hi-IN/tts",
        "bn" to "speech/languages/bn-IN/tts",
        "ta" to "speech/languages/ta-IN/tts",
        "te" to "speech/languages/te-IN/tts",
        "kn" to "speech/languages/kn-IN/tts",
        "ml" to "speech/languages/ml-IN/tts"
    )

    fun ttsFolder(lang: String): String =
        ttsFolderByCode[lang] ?: ttsFolderByCode.getValue(DEFAULT)

    fun asrSpec(lang: String): AsrSpec =
        if (lang == "en") {
            AsrSpec("speech/shared/sherpa-asr-en", SttMode.TRANSDUCER, "en")
        } else {
            AsrSpec("speech/shared/sherpa-asr-whisper", SttMode.WHISPER, lang)
        }

    const val KWS_FOLDER = "speech/shared/vad"

    fun isSupported(code: String): Boolean = SUPPORTED.any { it.code == code }

    fun normalize(code: String?): String =
        if (!code.isNullOrBlank() && isSupported(code)) code else DEFAULT

    fun displayName(code: String): String =
        SUPPORTED.firstOrNull { it.code == code }?.display ?: displayName(DEFAULT)
}
