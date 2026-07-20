package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode

/**
 * Temporary compatibility mapping for the speech models already supported by UnoOne.
 *
 * The next migration phase replaces this hard-coded catalogue with signed downloadable language
 * packs. Until then, every path here must match `models_manifest.json` exactly so the normalized
 * model filesystem does not break the existing offline voice runtime.
 */
data class AsrSpec(val folder: String, val mode: SttMode, val language: String)

object VoiceLanguage {

    const val PREF_NAME = "unoone_settings"
    const val PREF_KEY = "voice_language"
    const val DEFAULT = "en"

    data class Lang(val code: String, val display: String)

    val SUPPORTED: List<Lang> = listOf(
        Lang("en", "English"),
        Lang("hi", "Hindi")
    )

    private val ttsFolderByCode: Map<String, String> = mapOf(
        "en" to "speech/languages/en-IN/tts",
        "hi" to "speech/languages/hi-IN/tts"
    )

    fun ttsFolder(lang: String): String =
        ttsFolderByCode[lang] ?: ttsFolderByCode.getValue(DEFAULT)

    fun asrSpec(lang: String): AsrSpec =
        if (lang == "en") {
            AsrSpec("speech/shared/sherpa-asr-en", SttMode.TRANSDUCER, "en")
        } else {
            AsrSpec("speech/shared/sherpa-asr-indic", SttMode.OMNILINGUAL, lang)
        }

    const val KWS_FOLDER = "speech/shared/vad"

    /**
     * Wake-word models in priority order. The dedicated KWS download and the English streaming
     * ASR use the same transducer files, so the already-installed English model is a safe offline
     * fallback when the optional `vad` model was not downloaded.
     */
    fun kwsFolders(): List<String> = listOf(KWS_FOLDER, asrSpec(DEFAULT).folder).distinct()

    fun isSupported(code: String): Boolean = SUPPORTED.any { it.code == code }

    fun normalize(code: String?): String =
        if (!code.isNullOrBlank() && isSupported(code)) code else DEFAULT

    fun displayName(code: String): String =
        SUPPORTED.firstOrNull { it.code == code }?.display ?: displayName(DEFAULT)

    /** Android locale tag for system STT/TTS fallbacks. Never silently falls back to en-US. */
    fun localeTag(code: String): String = when (normalize(code)) {
        "hi" -> "hi-IN"
        else -> "en-IN"
    }

    /** Short native-script phrase used by the Settings and Voice Test diagnostics. */
    fun testPhrase(code: String): String = when (normalize(code)) {
        "hi" -> "नमस्ते, यूनोवन की ऑफ़लाइन आवाज़ काम कर रही है।"
        else -> "Hello, UnoOne offline voice is working."
    }

    /** Native-language acknowledgement spoken after hands-free wake activation. */
    fun wakeCue(code: String): String = when (normalize(code)) {
        "hi" -> "हाँ, आवाज़ सुनाई दे रही है।"
        else -> "Yes, I'm listening."
    }

    /**
     * Recognizes only explicit voice-language change requests. Merely mentioning Hindi or English
     * in a message must not rebuild the speech runtime.
     */
    fun requestedFromCommand(command: String): String? {
        val normalized = command
            .lowercase()
            .replace(Regex("""[^\p{L}\p{M}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isBlank()) return null

        val hindiRequests = listOf(
            "speak in hindi",
            "reply in hindi",
            "answer in hindi",
            "switch to hindi",
            "change language to hindi",
            "hindi mein bolo",
            "hindi me bolo",
            "hindi mein jawab do",
            "hindi me jawab do",
            "हिंदी में बोलो",
            "हिन्दी में बोलो",
            "हिंदी में जवाब दो",
            "अब हिंदी में बोलो"
        )
        val englishRequests = listOf(
            "speak in english",
            "reply in english",
            "answer in english",
            "switch to english",
            "change language to english",
            "english mein bolo",
            "english me bolo",
            "अंग्रेज़ी में बोलो",
            "अंग्रेजी में बोलो",
            "इंग्लिश में बोलो"
        )
        return when {
            hindiRequests.any { normalized == it || normalized.startsWith("$it ") } -> "hi"
            englishRequests.any { normalized == it || normalized.startsWith("$it ") } -> "en"
            else -> null
        }
    }

    fun changeConfirmation(code: String): String = when (normalize(code)) {
        "hi" -> "अब जवाब हिंदी में होगा।"
        else -> "I will speak in English now."
    }

    fun changeFailure(requestedCode: String, responseCode: String): String =
        when (normalize(responseCode)) {
            "hi" -> if (normalize(requestedCode) == "hi") {
                "हिंदी की ऑफ़लाइन आवाज़ अभी उपलब्ध नहीं है।"
            } else {
                "अंग्रेज़ी की ऑफ़लाइन आवाज़ अभी उपलब्ध नहीं है।"
            }
            else -> "The offline ${displayName(requestedCode)} voice is not available yet."
        }
}
