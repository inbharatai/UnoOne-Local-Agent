package com.unoone.agent.voice

import com.unoone.agent.voice.stt.SttMode

/**
 * Offline voice language selection — the mapping from a language code to the on-disk Sherpa model
 * folders and STT mode. Pure (no Android/native deps beyond [SttMode]); unit-tested in
 * `VoiceLanguageMappingTest`.
 *
 * The active language is persisted as a string in `SharedPreferences("unoone_settings")` under
 * [PREF_KEY] (default "en"), written from Settings and read by [VoiceService] and [VoiceModule].
 *
 * Model layout (matches `models_manifest.json`):
 * - English: ASR `sherpa-asr-en` (streaming transducer), TTS `sherpa-tts-en` (Coqui/espeak).
 * - Hindi/Bengali/Tamil/Telugu/Kannada/Malayalam: ASR `sherpa-asr-whisper` (multilingual whisper-tiny
 *   int8, shared — the `language` field selects the language), TTS `sherpa-tts-<mms>` (MMS, one model
 *   per language). The wake-word (vad) stays English regardless of [lang] — no Indic KWS model exists.
 */
data class AsrSpec(val folder: String, val mode: SttMode, val whisperLanguage: String)

object VoiceLanguage {

    /** SharedPreferences store — shared with dark_mode etc. in `unoone_settings`. */
    const val PREF_NAME = "unoone_settings"
    const val PREF_KEY = "voice_language"
    const val DEFAULT = "en"

    data class Lang(val code: String, val display: String)

    /** The languages offered in Settings, in display order. */
    val SUPPORTED: List<Lang> = listOf(
        Lang("en", "English"),
        Lang("hi", "Hindi"),
        Lang("bn", "Bengali"),
        Lang("ta", "Tamil"),
        Lang("te", "Telugu"),
        Lang("kn", "Kannada"),
        Lang("ml", "Malayalam"),
    )

    /**
     * TTS folder per language. willwade MMS uses 3-letter ISO-639-3 codes (hin/ben/tam/tel/kan/mal);
     * English uses the Coqui folder. Keys are the 2-letter codes used throughout the app.
     */
    private val ttsFolderByCode: Map<String, String> = mapOf(
        "en" to "sherpa-tts-en",
        "hi" to "sherpa-tts-hin",
        "bn" to "sherpa-tts-ben",
        "ta" to "sherpa-tts-tam",
        "te" to "sherpa-tts-tel",
        "kn" to "sherpa-tts-kan",
        "ml" to "sherpa-tts-mal",
    )

    /** The on-disk TTS model folder for [lang] (falls back to English). */
    fun ttsFolder(lang: String): String = ttsFolderByCode[lang] ?: ttsFolderByCode.getValue(DEFAULT)

    /**
     * The ASR folder + STT mode + whisper `language` for [lang]. English uses the streaming
     * transducer; every Indic language uses the shared whisper-tiny model with its 2-letter code as
     * the whisper `language` (whisper-tiny multilingual supports hi/bn/ta/te/kn/ml).
     */
    fun asrSpec(lang: String): AsrSpec =
        if (lang == "en") AsrSpec("sherpa-asr-en", SttMode.TRANSDUCER, "en")
        else AsrSpec("sherpa-asr-whisper", SttMode.WHISPER, lang)

    /** The wake-word (vad) folder — always English (no Indic KWS model exists). */
    const val KWS_FOLDER = "vad"

    fun isSupported(code: String): Boolean = SUPPORTED.any { it.code == code }

    /** Returns [code] if supported, else [DEFAULT]. Tolerates null/blank. */
    fun normalize(code: String?): String =
        if (!code.isNullOrBlank() && isSupported(code)) code else DEFAULT

    fun displayName(code: String): String =
        SUPPORTED.firstOrNull { it.code == code }?.display ?: displayName(DEFAULT)
}