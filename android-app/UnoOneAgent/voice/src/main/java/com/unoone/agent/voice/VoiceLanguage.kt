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
        "bn" -> "bn-IN"
        "ta" -> "ta-IN"
        "te" -> "te-IN"
        "kn" -> "kn-IN"
        "ml" -> "ml-IN"
        else -> "en-IN"
    }

    /** Short native-script phrase used by the Settings and Voice Test diagnostics. */
    fun testPhrase(code: String): String = when (normalize(code)) {
        "hi" -> "नमस्ते, यूनोवन की ऑफ़लाइन आवाज़ काम कर रही है।"
        "bn" -> "নমস্কার, ইউনোওয়ানের অফলাইন কণ্ঠস্বর কাজ করছে।"
        "ta" -> "வணக்கம், யூனோஒன் ஆஃப்லைன் குரல் வேலை செய்கிறது."
        "te" -> "నమస్కారం, యునోవన్ ఆఫ్‌లైన్ వాయిస్ పనిచేస్తోంది."
        "kn" -> "ನಮಸ್ಕಾರ, ಯುನೋಒನ್ ಆಫ್‌ಲೈನ್ ಧ್ವನಿ ಕೆಲಸ ಮಾಡುತ್ತಿದೆ."
        "ml" -> "നമസ്കാരം, യൂനോവൺ ഓഫ്‌ലൈൻ ശബ്ദം പ്രവർത്തിക്കുന്നു."
        else -> "Hello, UnoOne offline voice is working."
    }

    /** Native-language acknowledgement spoken after hands-free wake activation. */
    fun wakeCue(code: String): String = when (normalize(code)) {
        "hi" -> "हाँ, मैं सुन रही हूँ।"
        "bn" -> "হ্যাঁ, আমি শুনছি।"
        "ta" -> "ஆம், நான் கேட்கிறேன்."
        "te" -> "అవును, నేను వింటున్నాను."
        "kn" -> "ಹೌದು, ನಾನು ಕೇಳುತ್ತಿದ್ದೇನೆ."
        "ml" -> "അതെ, ഞാൻ കേൾക്കുന്നു."
        else -> "Yes, I'm listening."
    }
}
