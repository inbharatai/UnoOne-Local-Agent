package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode

/**
 * Offline speech mapping for the two reply languages supported by UnoOne.
 *
 * The next migration phase replaces this hard-coded catalogue with signed downloadable language
 * packs. Until then, every path here must match `models_manifest.json` exactly so the normalized
 * model filesystem does not break the existing offline voice runtime.
 */
data class AsrSpec(val folder: String, val mode: SttMode, val language: String)

object VoiceLanguage {
    data class LanguageRequest(
        val code: String,
        val remainingCommand: String
    )

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

    const val BILINGUAL_ASR_FOLDER = "speech/shared/sherpa-asr-indic"
    const val ENGLISH_TRANSCRIBER_FOLDER = "speech/shared/sherpa-asr-en"

    fun ttsFolder(lang: String): String =
        ttsFolderByCode[lang] ?: ttsFolderByCode.getValue(DEFAULT)

    /**
     * Input recognition is bilingual and independent from the selected reply/TTS language.
     * Omnilingual CTC detects English, Hindi and code-switched speech from the audio itself.
     */
    fun inputAsrSpec(): AsrSpec =
        AsrSpec(BILINGUAL_ASR_FOLDER, SttMode.OMNILINGUAL, "auto")

    /**
     * Compatibility alias retained for model-install and diagnostic callers. [lang] intentionally
     * does not select the recognizer; it selects only the reply voice elsewhere in the runtime.
     */
    fun asrSpec(@Suppress("UNUSED_PARAMETER") lang: String): AsrSpec = inputAsrSpec()

    const val KWS_FOLDER = "speech/shared/sherpa-kws-en"

    /**
     * Wake-word models in priority order. The dedicated KWS download and the English streaming
     * ASR use the same transducer files, so the already-installed English model is a safe offline
     * fallback when the optional KWS model was not downloaded. This is not a VAD model.
     */
    fun kwsFolders(): List<String> = listOf(KWS_FOLDER, ENGLISH_TRANSCRIBER_FOLDER).distinct()

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

    fun retryCue(code: String): String = when (normalize(code)) {
        "hi" -> "आवाज़ साफ़ नहीं आई। कृपया फिर से बोलें।"
        else -> "I didn't hear that clearly. Please try again."
    }

    /**
     * Recognizes only explicit voice-language change requests. Merely mentioning Hindi or English
     * in a message must not rebuild the speech runtime.
     */
    private val hindiRequests = listOf(
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
    private val englishRequests = listOf(
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

    /**
     * Extracts an explicit language instruction even when it follows a real action, for example
     * "blind mode start karo aur Hindi mein jawab do". Only complete imperative phrases match;
     * ordinary mentions such as "Hindi class" or "English calendar" remain untouched.
     */
    fun extractRequest(command: String): LanguageRequest? {
        val candidates = (hindiRequests.map { "hi" to it } + englishRequests.map { "en" to it })
            .sortedByDescending { it.second.length }
        val match = candidates.firstNotNullOfOrNull { (code, phrase) ->
            val regex = Regex(
                "(?iu)(?<![\\p{L}\\p{M}])${Regex.escape(phrase)}(?![\\p{L}\\p{M}])"
            )
            regex.find(command)?.let { code to it.range }
        } ?: return null

        val remaining = command.removeRange(match.second)
            .replace(Regex("(?iu)^\\s*(?:and|aur|और)\\s+"), "")
            .replace(Regex("(?iu)\\s+(?:and|aur|और)\\s*$"), "")
            .trim(' ', ',', '.', ';', ':', '-', '।')
            .replace(Regex("\\s+"), " ")
        return LanguageRequest(match.first, remaining)
    }

    fun requestedFromCommand(command: String): String? = extractRequest(command)?.code

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
