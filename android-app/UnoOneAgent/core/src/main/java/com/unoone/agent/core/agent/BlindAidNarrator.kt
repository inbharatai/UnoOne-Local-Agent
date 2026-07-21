package com.unoone.agent.core.agent

/**
 * Eyes-free (WS3): decides when and what the Blind Aid camera should speak as a periodic scene
 * summary ("In front of you: a chair, a desk, a person"), separate from the close-obstacle
 * warnings. Pure logic so the throttle + summary wording are JVM-testable without CameraX/ML Kit;
 * [com.unoone.agent.phonecontrol.BlindAidManager] calls this from its detection callback.
 *
 * Throttle model: a scene change (the set of distinct labels changed) may be narrated at most every
 * [DEFAULT_CHANGE_INTERVAL_MS]; a steady, unchanged scene is re-narrated at most every
 * [DEFAULT_STEADY_INTERVAL_MS] so a blind user still gets a periodic "still here" update without
 * per-frame chatter. Label flicker is absorbed by the change interval rather than triggering a
 * speak on every frame. Quiet mode suppresses all scene narration (obstacle warnings are handled
 * separately by the manager).
 */
object BlindAidNarrator {

    /** Min gap between scene narrations when the label set has changed. */
    const val DEFAULT_CHANGE_INTERVAL_MS = 10_000L

    /** Min gap between unchanged-scene reminders; avoid repeating a cached observation. */
    const val DEFAULT_STEADY_INTERVAL_MS = 30_000L

    /**
     * Build a spoken scene summary from the detected labels. Returns "" when there is nothing
     * meaningful to say (no labels, or only the generic "obstacle" fallback). Distinct labels are
     * lowercased and preceded by an article ("a chair", "an apple"). The generic "obstacle" label
     * is dropped because it carries no scene information for a blind user.
     */
    fun sceneSummary(labels: Collection<String>, languageCode: String = "en"): String {
        val distinct = labels.mapNotNull { it.trim().lowercase().ifBlank { null } }
            .filter { it != "obstacle" }
            .distinct()
        if (distinct.isEmpty()) return ""
        if (languageCode.isHindi()) {
            return "सामने: " + distinct.joinToString(", ") { spokenLabel(it, languageCode) } + "।"
        }
        return "In front of you: " + distinct.joinToString(", ") { withArticle(it) } + "."
    }

    /**
     * A short, native-language warning for the closest confirmed object. Detector labels remain in
     * English on the visual overlay, while speech uses Hindi names so the Hindi MMS voice never has
     * to guess how to pronounce English COCO labels.
     */
    fun proximityWarning(label: String, immediate: Boolean, languageCode: String = "en"): String {
        val spoken = spokenLabel(label, languageCode)
        return if (languageCode.isHindi()) {
            if (immediate) "रुकिए। सामने $spoken है।" else "$spoken सामने है।"
        } else {
            if (immediate) "Stop! $spoken is directly in front of you." else "$spoken ahead."
        }
    }

    fun activationMessage(languageCode: String): String =
        if (languageCode.isHindi()) {
            "ब्लाइंड मोड चालू हो गया है। अब आप आवाज़ से UnoOne को नियंत्रित कर सकते हैं। " +
                "कैमरा सामने की चीज़ें पहचान रहा है। सुरक्षा के लिए छड़ी या सहायक का भी उपयोग करें।"
        } else {
            "Blind Aid activated. Scanning for obstacles ahead. " +
                "This is assistive guidance only, not a certified navigation device. " +
                "Please use a cane or a guide and normal safety precautions."
        }

    fun deactivationMessage(languageCode: String): String =
        if (languageCode.isHindi()) "ब्लाइंड मोड बंद हो गया है।" else "Blind Aid deactivated."

    fun spokenLabel(label: String, languageCode: String): String {
        val normalized = label.trim().lowercase().replace('_', ' ')
        if (!languageCode.isHindi()) return normalized
        return hindiObjectLabels[normalized] ?: normalized
    }

    /**
     * Decide whether a scene narration should fire now. Returns false in quiet mode, when the
     * current label set is empty/only "obstacle", or when not enough time has elapsed since the last
     * narration ([lastNarrationMs]). A label-set change uses the shorter [changeIntervalMs]; an
     * unchanged scene uses the longer [steadyIntervalMs].
     */
    fun shouldNarrateScene(
        nowMs: Long,
        lastNarrationMs: Long,
        lastLabels: Set<String>,
        currentLabels: Set<String>,
        quietMode: Boolean,
        changeIntervalMs: Long = DEFAULT_CHANGE_INTERVAL_MS,
        steadyIntervalMs: Long = DEFAULT_STEADY_INTERVAL_MS
    ): Boolean {
        if (quietMode) return false
        val current = normalize(currentLabels)
        if (current.isEmpty()) return false
        val changed = current != normalize(lastLabels)
        val interval = if (changed) changeIntervalMs else steadyIntervalMs
        return nowMs - lastNarrationMs >= interval
    }

    /** Normalize a label set the same way [sceneSummary] / [shouldNarrateScene] do. */
    fun normalize(labels: Set<String>): Set<String> =
        labels.mapNotNull { it.trim().lowercase().ifBlank { null } }
            .filter { it != "obstacle" }
            .toSet()

    private fun withArticle(noun: String): String =
        if (noun.startsWithVowel()) "an $noun" else "a $noun"

    private fun String.startsWithVowel(): Boolean =
        isNotEmpty() && first().lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')

    private fun String.isHindi(): Boolean = lowercase().startsWith("hi")

    // All 80 COCO classes used by the bundled EfficientDet model. Keeping the translation at the
    // narration boundary means detection, confidence filtering and on-screen labels remain stable.
    private val hindiObjectLabels = mapOf(
        "person" to "व्यक्ति",
        "bicycle" to "साइकिल",
        "car" to "कार",
        "motorcycle" to "मोटरसाइकिल",
        "airplane" to "हवाई जहाज़",
        "bus" to "बस",
        "train" to "ट्रेन",
        "truck" to "ट्रक",
        "boat" to "नाव",
        "traffic light" to "ट्रैफिक लाइट",
        "fire hydrant" to "फायर हाइड्रेंट",
        "stop sign" to "रुकने का संकेत",
        "parking meter" to "पार्किंग मीटर",
        "bench" to "बेंच",
        "bird" to "पक्षी",
        "cat" to "बिल्ली",
        "dog" to "कुत्ता",
        "horse" to "घोड़ा",
        "sheep" to "भेड़",
        "cow" to "गाय",
        "elephant" to "हाथी",
        "bear" to "भालू",
        "zebra" to "ज़ेब्रा",
        "giraffe" to "जिराफ़",
        "backpack" to "पीठ का बैग",
        "umbrella" to "छाता",
        "handbag" to "हैंडबैग",
        "tie" to "टाई",
        "suitcase" to "सूटकेस",
        "frisbee" to "फ्रिस्बी",
        "skis" to "स्की",
        "snowboard" to "स्नोबोर्ड",
        "sports ball" to "गेंद",
        "kite" to "पतंग",
        "baseball bat" to "बेसबॉल बैट",
        "baseball glove" to "बेसबॉल दस्ताना",
        "skateboard" to "स्केटबोर्ड",
        "surfboard" to "सर्फबोर्ड",
        "tennis racket" to "टेनिस रैकेट",
        "bottle" to "बोतल",
        "wine glass" to "काँच का गिलास",
        "cup" to "कप",
        "fork" to "काँटा",
        "knife" to "चाकू",
        "spoon" to "चम्मच",
        "bowl" to "कटोरा",
        "banana" to "केला",
        "apple" to "सेब",
        "sandwich" to "सैंडविच",
        "orange" to "संतरा",
        "broccoli" to "ब्रोकोली",
        "carrot" to "गाजर",
        "hot dog" to "हॉट डॉग",
        "pizza" to "पिज़्ज़ा",
        "donut" to "डोनट",
        "cake" to "केक",
        "chair" to "कुर्सी",
        "couch" to "सोफ़ा",
        "potted plant" to "गमले का पौधा",
        "bed" to "बिस्तर",
        "dining table" to "मेज़",
        "toilet" to "शौचालय",
        "tv" to "टेलीविज़न",
        "laptop" to "लैपटॉप",
        "mouse" to "माउस",
        "remote" to "रिमोट",
        "keyboard" to "कीबोर्ड",
        "cell phone" to "मोबाइल फोन",
        "microwave" to "माइक्रोवेव",
        "oven" to "ओवन",
        "toaster" to "टोस्टर",
        "sink" to "सिंक",
        "refrigerator" to "फ्रिज",
        "book" to "किताब",
        "clock" to "घड़ी",
        "vase" to "फूलदान",
        "scissors" to "कैंची",
        "teddy bear" to "टेडी बियर",
        "hair drier" to "हेयर ड्रायर",
        "toothbrush" to "टूथब्रश"
    )
}
