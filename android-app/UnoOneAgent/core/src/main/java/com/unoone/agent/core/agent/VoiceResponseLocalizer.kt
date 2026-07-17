package com.unoone.agent.core.agent

/**
 * Deterministic, offline localization for agent status speech.
 *
 * Tool implementations return technical English observations for logs and verification. Reading
 * those strings through an Indic voice sounds broken and is inaccessible to an eyes-free user.
 * This localizer keeps content-bearing results (OCR, notes, summaries and scene descriptions)
 * intact, while giving side-effect tools and progress milestones a short native-language status.
 * It never calls a model or network service and therefore remains available in airplane mode.
 */
object VoiceResponseLocalizer {

    private val contentTools = setOf(
        "search_notes",
        "summarize_text",
        "speak_response",
        "read_screen",
        "ocr_screen",
        "describe_scene",
        "web_search"
    )

    fun toolResult(tool: String, original: String, language: String): String {
        val lang = language.substringBefore('-').lowercase()
        if (lang == "en" || tool in contentTools) return original
        if (tool == "check_calendar") return calendarResult(original, lang)
        return when (tool) {
            "open_chrome", "open_app", "open_url", "open_camera", "open_calendar",
            "open_calendar_insert", "open_dialer", "prepare_document_fill",
            "secure_browser_task", "send_whatsapp" -> phrase(lang, Kind.OPENING)

            "draft_email", "share_text" -> phrase(lang, Kind.DRAFT_READY)
            "create_note", "create_skill", "voice_recording", "export_data" ->
                phrase(lang, Kind.SAVED)

            "delete_notes", "delete_all_notes" -> phrase(lang, Kind.DELETED)
            "detect_objects" -> phrase(lang, Kind.BLIND_ON)
            "deactivate_blind_aid" -> phrase(lang, Kind.BLIND_OFF)
            else -> phrase(lang, Kind.COMPLETE)
        }
    }

    /** A short non-technical failure spoken in the selected language; details stay in the audit. */
    fun failure(language: String): String {
        val lang = language.substringBefore('-').lowercase()
        return failures[lang] ?: failures.getValue("en")
    }

    fun milestone(original: String, language: String): String {
        val lang = language.substringBefore('-').lowercase()
        if (lang == "en") return original
        val kind = when {
            original.startsWith("Opening", ignoreCase = true) -> Kind.OPENING
            original.startsWith("Starting", ignoreCase = true) -> Kind.STARTING
            original.startsWith("Stopping", ignoreCase = true) -> Kind.STOPPING
            original.startsWith("Checking", ignoreCase = true) -> Kind.CHECKING
            original.startsWith("Reading", ignoreCase = true) -> Kind.READING
            original.startsWith("Searching", ignoreCase = true) -> Kind.SEARCHING
            original.startsWith("Creating", ignoreCase = true) -> Kind.CREATING
            original.startsWith("Drafting", ignoreCase = true) -> Kind.DRAFTING
            original.startsWith("Deleting", ignoreCase = true) -> Kind.DELETING
            original.startsWith("Verifying", ignoreCase = true) -> Kind.VERIFYING
            else -> Kind.WORKING
        }
        return phrase(lang, kind)
    }

    private enum class Kind {
        OPENING, STARTING, STOPPING, CHECKING, READING, SEARCHING, CREATING, DRAFTING,
        DELETING, VERIFYING, WORKING, DRAFT_READY, SAVED, DELETED, BLIND_ON, BLIND_OFF,
        COMPLETE
    }

    private fun phrase(language: String, kind: Kind): String {
        val row = phrases[language] ?: phrases.getValue("en")
        return row.getValue(kind)
    }

    private fun calendarResult(original: String, language: String): String {
        if (original.equals("Calendar clear today.", ignoreCase = true)) {
            return calendarClear[language] ?: calendarClear.getValue("en")
        }
        val count = Regex("""\b(\d+)\b""").find(original)?.groupValues?.get(1)
            ?: return phrase(language, Kind.COMPLETE)
        return when (language) {
            "hi" -> "आज आपके $count कार्यक्रम हैं।"
            "bn" -> "আজ আপনার ${count}টি অনুষ্ঠান আছে।"
            "ta" -> "இன்று உங்களுக்கு $count நிகழ்வுகள் உள்ளன."
            "te" -> "ఈ రోజు మీకు $count ఈవెంట్‌లు ఉన్నాయి."
            "kn" -> "ಇಂದು ನಿಮಗೆ $count ಕಾರ್ಯಕ್ರಮಗಳಿವೆ."
            "ml" -> "ഇന്ന് നിങ്ങൾക്ക് $count പരിപാടികളുണ്ട്."
            else -> "You have $count events today."
        }
    }

    private val failures = mapOf(
        "en" to "The task could not be completed. Please try again.",
        "hi" to "काम पूरा नहीं हो सका। कृपया फिर कोशिश करें।",
        "bn" to "কাজটি সম্পন্ন করা যায়নি। আবার চেষ্টা করুন।",
        "ta" to "பணியை முடிக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும்.",
        "te" to "పనిని పూర్తి చేయలేకపోయాను. మళ్లీ ప్రయత్నించండి.",
        "kn" to "ಕೆಲಸವನ್ನು ಪೂರ್ಣಗೊಳಿಸಲಾಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ.",
        "ml" to "പ്രവർത്തി പൂർത്തിയാക്കാനായില്ല. വീണ്ടും ശ്രമിക്കുക."
    )

    private val calendarClear = mapOf(
        "en" to "Your calendar is clear today.",
        "hi" to "आज आपके कैलेंडर में कोई कार्यक्रम नहीं है।",
        "bn" to "আজ আপনার ক্যালেন্ডারে কোনো অনুষ্ঠান নেই।",
        "ta" to "இன்று உங்கள் நாட்காட்டியில் நிகழ்வுகள் இல்லை.",
        "te" to "ఈ రోజు మీ క్యాలెండర్‌లో ఈవెంట్‌లు లేవు.",
        "kn" to "ಇಂದು ನಿಮ್ಮ ಕ್ಯಾಲೆಂಡರ್‌ನಲ್ಲಿ ಯಾವುದೇ ಕಾರ್ಯಕ್ರಮಗಳಿಲ್ಲ.",
        "ml" to "ഇന്ന് നിങ്ങളുടെ കലണ്ടറിൽ പരിപാടികളൊന്നുമില്ല."
    )

    private val phrases: Map<String, Map<Kind, String>> = mapOf(
        "en" to english(),
        "hi" to row(
            opening = "खोल रहा हूँ।", starting = "शुरू कर रहा हूँ।", stopping = "बंद कर रहा हूँ।",
            checking = "जाँच रहा हूँ।", reading = "पढ़ रहा हूँ।", searching = "खोज रहा हूँ।",
            creating = "बना रहा हूँ।", drafting = "ड्राफ्ट बना रहा हूँ।", deleting = "हटा रहा हूँ।",
            verifying = "परिणाम जाँच रहा हूँ।", working = "काम कर रहा हूँ।",
            draftReady = "ड्राफ्ट समीक्षा के लिए तैयार है।", saved = "सहेज दिया गया है।",
            deleted = "हटा दिया गया है।", blindOn = "ब्लाइंड एड शुरू हो गया है।",
            blindOff = "ब्लाइंड एड बंद हो गया है।", complete = "काम पूरा हो गया है।"
        ),
        "bn" to row(
            opening = "খুলছি।", starting = "শুরু করছি।", stopping = "বন্ধ করছি।",
            checking = "যাচাই করছি।", reading = "পড়ছি।", searching = "খুঁজছি।",
            creating = "তৈরি করছি।", drafting = "খসড়া তৈরি করছি।", deleting = "মুছে দিচ্ছি।",
            verifying = "ফলাফল যাচাই করছি।", working = "কাজ করছি।",
            draftReady = "খসড়াটি পর্যালোচনার জন্য প্রস্তুত।", saved = "সংরক্ষণ করা হয়েছে।",
            deleted = "মুছে ফেলা হয়েছে।", blindOn = "ব্লাইন্ড এইড চালু হয়েছে।",
            blindOff = "ব্লাইন্ড এইড বন্ধ হয়েছে।", complete = "কাজ সম্পন্ন হয়েছে।"
        ),
        "ta" to row(
            opening = "திறக்கிறேன்.", starting = "தொடங்குகிறேன்.", stopping = "நிறுத்துகிறேன்.",
            checking = "சரிபார்க்கிறேன்.", reading = "படிக்கிறேன்.", searching = "தேடுகிறேன்.",
            creating = "உருவாக்குகிறேன்.", drafting = "வரைவை உருவாக்குகிறேன்.", deleting = "நீக்குகிறேன்.",
            verifying = "முடிவை சரிபார்க்கிறேன்.", working = "செயல்படுகிறேன்.",
            draftReady = "வரைவு மதிப்பாய்வுக்கு தயாராக உள்ளது.", saved = "சேமிக்கப்பட்டது.",
            deleted = "நீக்கப்பட்டது.", blindOn = "பிளைண்ட் எய்ட் தொடங்கியது.",
            blindOff = "பிளைண்ட் எய்ட் நிறுத்தப்பட்டது.", complete = "பணி முடிந்தது."
        ),
        "te" to row(
            opening = "తెరుస్తున్నాను.", starting = "ప్రారంభిస్తున్నాను.", stopping = "ఆపుతున్నాను.",
            checking = "తనిఖీ చేస్తున్నాను.", reading = "చదువుతున్నాను.", searching = "వెతుకుతున్నాను.",
            creating = "సృష్టిస్తున్నాను.", drafting = "డ్రాఫ్ట్ సిద్ధం చేస్తున్నాను.", deleting = "తొలగిస్తున్నాను.",
            verifying = "ఫలితాన్ని తనిఖీ చేస్తున్నాను.", working = "పని చేస్తున్నాను.",
            draftReady = "డ్రాఫ్ట్ సమీక్షకు సిద్ధంగా ఉంది.", saved = "సేవ్ చేయబడింది.",
            deleted = "తొలగించబడింది.", blindOn = "బ్లైండ్ ఎయిడ్ ప్రారంభమైంది.",
            blindOff = "బ్లైండ్ ఎయిడ్ ఆగింది.", complete = "పని పూర్తయింది."
        ),
        "kn" to row(
            opening = "ತೆರೆಯುತ್ತಿದ್ದೇನೆ.", starting = "ಪ್ರಾರಂಭಿಸುತ್ತಿದ್ದೇನೆ.", stopping = "ನಿಲ್ಲಿಸುತ್ತಿದ್ದೇನೆ.",
            checking = "ಪರಿಶೀಲಿಸುತ್ತಿದ್ದೇನೆ.", reading = "ಓದುತ್ತಿದ್ದೇನೆ.", searching = "ಹುಡುಕುತ್ತಿದ್ದೇನೆ.",
            creating = "ರಚಿಸುತ್ತಿದ್ದೇನೆ.", drafting = "ಕರಡು ಸಿದ್ಧಪಡಿಸುತ್ತಿದ್ದೇನೆ.", deleting = "ಅಳಿಸುತ್ತಿದ್ದೇನೆ.",
            verifying = "ಫಲಿತಾಂಶ ಪರಿಶೀಲಿಸುತ್ತಿದ್ದೇನೆ.", working = "ಕೆಲಸ ಮಾಡುತ್ತಿದ್ದೇನೆ.",
            draftReady = "ಕರಡು ಪರಿಶೀಲನೆಗೆ ಸಿದ್ಧವಾಗಿದೆ.", saved = "ಉಳಿಸಲಾಗಿದೆ.",
            deleted = "ಅಳಿಸಲಾಗಿದೆ.", blindOn = "ಬ್ಲೈಂಡ್ ಏಡ್ ಪ್ರಾರಂಭವಾಗಿದೆ.",
            blindOff = "ಬ್ಲೈಂಡ್ ಏಡ್ ನಿಂತಿದೆ.", complete = "ಕೆಲಸ ಪೂರ್ಣಗೊಂಡಿದೆ."
        ),
        "ml" to row(
            opening = "തുറക്കുന്നു.", starting = "ആരംഭിക്കുന്നു.", stopping = "നിർത്തുന്നു.",
            checking = "പരിശോധിക്കുന്നു.", reading = "വായിക്കുന്നു.", searching = "തിരയുന്നു.",
            creating = "സൃഷ്ടിക്കുന്നു.", drafting = "ഡ്രാഫ്റ്റ് തയ്യാറാക്കുന്നു.", deleting = "നീക്കം ചെയ്യുന്നു.",
            verifying = "ഫലം പരിശോധിക്കുന്നു.", working = "പ്രവർത്തിക്കുന്നു.",
            draftReady = "ഡ്രാഫ്റ്റ് പരിശോധിക്കാൻ തയ്യാറാണ്.", saved = "സൂക്ഷിച്ചു.",
            deleted = "നീക്കം ചെയ്തു.", blindOn = "ബ്ലൈൻഡ് എയ്ഡ് ആരംഭിച്ചു.",
            blindOff = "ബ്ലൈൻഡ് എയ്ഡ് നിർത്തി.", complete = "പ്രവർത്തി പൂർത്തിയായി."
        )
    )

    private fun english(): Map<Kind, String> = row(
        opening = "Opening.", starting = "Starting.", stopping = "Stopping.",
        checking = "Checking.", reading = "Reading.", searching = "Searching.",
        creating = "Creating.", drafting = "Preparing a draft.", deleting = "Deleting.",
        verifying = "Verifying the result.", working = "Working.",
        draftReady = "The draft is ready for review.", saved = "Saved.",
        deleted = "Deleted.", blindOn = "Blind Aid started.", blindOff = "Blind Aid stopped.",
        complete = "Task completed."
    )

    private fun row(
        opening: String,
        starting: String,
        stopping: String,
        checking: String,
        reading: String,
        searching: String,
        creating: String,
        drafting: String,
        deleting: String,
        verifying: String,
        working: String,
        draftReady: String,
        saved: String,
        deleted: String,
        blindOn: String,
        blindOff: String,
        complete: String
    ): Map<Kind, String> = mapOf(
        Kind.OPENING to opening,
        Kind.STARTING to starting,
        Kind.STOPPING to stopping,
        Kind.CHECKING to checking,
        Kind.READING to reading,
        Kind.SEARCHING to searching,
        Kind.CREATING to creating,
        Kind.DRAFTING to drafting,
        Kind.DELETING to deleting,
        Kind.VERIFYING to verifying,
        Kind.WORKING to working,
        Kind.DRAFT_READY to draftReady,
        Kind.SAVED to saved,
        Kind.DELETED to deleted,
        Kind.BLIND_ON to blindOn,
        Kind.BLIND_OFF to blindOff,
        Kind.COMPLETE to complete
    )
}
