package com.unoone.agent.localbrain

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fallback rule-based parser for commands when the local LLM is not loaded.
 * Handles 30+ command patterns including gestures, navigation, skills, and compound commands.
 *
 * Parser bugs fixed (Phase 4D):
 * - "barriers" alone now triggers deactivation only, not detection
 * - "note" substring now checks for negation verbs (delete/remove/cancel)
 * - "open google" vs "open google settings" now correctly prioritizes settings
 * - Email regex uses escaped dots: ([\\w.]+@[\\w]+\\.[\\w]+)
 * - Compound "A and B and C" now parses up to 3 parts (limit removed from 2)
 */
object RuleBasedParser {

    private fun hasAnyWord(value: String, vararg words: String): Boolean =
        words.any { word ->
            Regex("(^|\\s)${Regex.escape(word)}(?=\\s|[,.!?]|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(value)
        }

    private fun explicitWebTarget(value: String): String? =
        Regex(
            "(?<![@\\w])(?:https?://)?(?:www\\.)?[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)+(?:/[^\\s]*)?",
            RegexOption.IGNORE_CASE
        ).find(value)?.value?.trimEnd('.', ',', ';')

    // Domain-specific patterns that use "and" internally for their own semantics.
    // These MUST be checked BEFORE the compound handler splits on " and ".
    private val domainSpecificKeywords = listOf(
        "teach you", "create skill", "new skill",
        "email", "mail",
        "whatsapp",
        "calendar", "schedule", "events",
        "find and click", "find and tap", "find then click", "find then tap",
        // Secure Browser approved-origin friendly names — kept domain-specific so
        // "open unigurus and fill the form" is NOT split on "and" (the whole phrase becomes the
        // PageAgent task). See [com.unoone.agent.securebrowser.ApprovedOriginPolicy].
        "unigurus", "uni guru", "uniassist", "uni assist", "uni-assist",
        "testsprep", "tests prep", "inbharat", "in bharat", "secure browser"
    )

    // Negation verbs that suppress note creation when paired with "note"
    private val noteNegationVerbs = listOf("delete", "remove", "cancel", "close", "clear", "erase")

    // Friendly spoken names → canonical approved HTTPS origin, mirroring the authoritative
    // ApprovedOriginPolicy in :securebrowser. RuleBasedParser only PROPOSES secure_browser_task;
    // ActionExecutor re-validates via ApprovedOriginPolicy.originFor at execution time, so a stale
    // or missing entry here is rejected (never silently honored). Kept here (not imported from
    // :securebrowser) so :localbrain does not depend on the Android WebView layer.
    private val SECURE_ORIGIN_FRIENDLY: List<Pair<String, String>> = listOf(
        "unigurus" to "https://unigurus.com",
        "uni guru" to "https://unigurus.com",
        "uniassist" to "https://uniassist.ai",
        "uni assist" to "https://uniassist.ai",
        "uni-assist" to "https://uniassist.ai",
        "testsprep" to "https://testsprep.in",
        "tests prep" to "https://testsprep.in",
        "inbharat" to "https://inbharat.ai",
        "in bharat" to "https://inbharat.ai"
    )

    fun parse(command: String): ToolCall? {
        val lowered = command.lowercase().trim()
        val explicitTarget = explicitWebTarget(lowered)
        val startsWithOpenVerb = lowered.startsWith("open ") || lowered.startsWith("launch ") ||
            lowered.startsWith("start ") || lowered.startsWith("use ")
        parseLocalizedCoreCommand(lowered)?.let { return it }

        return when {
            // === DOMAIN-SPECIFIC RULES (use "and" internally — must be checked FIRST) ===

            // Skill Building — steps are joined with " and " / " then "
            lowered.contains("teach you") || lowered.contains("create skill") || lowered.contains("new skill") -> {
                val name = Regex("skill called (.*?) to").find(lowered)?.groupValues?.get(1) ?: "Custom Skill"
                val steps = lowered.substringAfter("to ").split(" then ", " and ").map { it.trim() }
                ToolCall(
                    "create_skill",
                    JsonObject(mapOf(
                        "name" to JsonPrimitive(name),
                        "steps" to JsonPrimitive(steps.joinToString("|"))
                    ))
                )
            }

            // A request to open/check Gmail is navigation, never an empty email draft.
            lowered in setOf(
                "open gmail", "open my gmail", "open gmail app", "launch gmail",
                "open email", "open my email", "check email", "check my email",
                "show inbox", "open inbox"
            ) -> ToolCall(
                "open_app",
                JsonObject(mapOf(
                    "app_name" to JsonPrimitive("gmail"),
                    "package_name" to JsonPrimitive("com.google.android.gm")
                ))
            )

            // Email Drafting. Only explicit compose/draft/write/send language reaches this branch;
            // an incidental word such as "email address" remains available to the normal planner.
            (lowered.contains("email") || lowered.contains("e-mail") || lowered.contains("mail")) &&
                hasAnyWord(lowered, "draft", "compose", "write", "send", "prepare") -> {
                val to = Regex(
                    "(?:to\\s+)?([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})",
                    RegexOption.IGNORE_CASE
                ).find(command)?.groupValues?.get(1) ?: ""
                val subject = Regex(
                    "\\bsubject\\s+(.+?)(?=\\s+(?:body|message|text)\\b|$)",
                    RegexOption.IGNORE_CASE
                ).find(command)?.groupValues?.get(1)?.trim() ?: "Update"
                val body = Regex(
                    "\\b(?:body|message|text)\\s+(.+)$",
                    RegexOption.IGNORE_CASE
                ).find(command)?.groupValues?.get(1)?.trim().orEmpty()
                ToolCall(
                    "draft_email",
                    JsonObject(mapOf(
                        "to" to JsonPrimitive(to),
                        "subject" to JsonPrimitive(subject),
                        "body" to JsonPrimitive(body)
                    ))
                )
            }

            // Opening WhatsApp is a launch action, not a message draft. Keep this before the
            // generic WhatsApp branch so "open WhatsApp" never becomes send_whatsapp with an empty
            // phone number (which the executor correctly rejects).
            lowered in setOf(
                "open whatsapp", "open my whatsapp", "open whatsapp app",
                "launch whatsapp", "start whatsapp", "show whatsapp"
            ) -> ToolCall(
                "open_app",
                JsonObject(mapOf(
                    "app_name" to JsonPrimitive("whatsapp"),
                    "package_name" to JsonPrimitive("com.whatsapp")
                ))
            )

            // WhatsApp Integration — prepare a draft only; the user presses Send in WhatsApp.
            // Without a spoken phone number, WhatsApp's own recipient picker is opened instead of
            // inventing or learning a contact mapping.
            lowered.contains("whatsapp") &&
                hasAnyWord(lowered, "message", "text", "write", "draft", "send", "saying", "say") -> {
                val number = Regex("(?:to|at) ([+]?[\\d]{8,15})").find(lowered)?.groupValues?.get(1) ?: ""
                val message = Regex(
                    "\\b(?:saying|message|text|say)\\b\\s*(.+)$",
                    RegexOption.IGNORE_CASE
                ).find(command)?.groupValues?.get(1)?.trim()
                    ?.let { Regex("^(?:saying|say)\\s+", RegexOption.IGNORE_CASE).replace(it, "") }
                    ?.let { Regex("^[+\\d][\\d\\s-]{7,}\\s*").replace(it, "").trim() }
                    .orEmpty()
                ToolCall(
                    "send_whatsapp",
                    JsonObject(mapOf(
                        "number" to JsonPrimitive(number),
                        "message" to JsonPrimitive(message)
                    ))
                )
            }

            // Open the calendar app (launch, not check/insert). Must run BEFORE the generic
            // "calendar"-keyword branch below: that branch returns null for plain "open calendar"
            // (no check/show/add verb), and a `when` expression does not fall through, so the
            // open_app catch-all never sees it. Resolves to a non-OEM launcher intent.
            lowered in setOf(
                "open calendar", "open the calendar", "open my calendar", "open calendar app",
                "launch calendar", "launch the calendar", "launch calendar app",
                "show calendar app", "show the calendar app"
            ) -> ToolCall("open_calendar", JsonObject(emptyMap()))

            // Calendar Intelligence
            lowered.contains("calendar") || lowered.contains("schedule") || lowered.contains("events") ||
                lowered.startsWith("remind me ") -> {
                if (lowered.contains("check") || lowered.contains("what") || lowered.contains("show") || lowered.contains("read")) {
                    ToolCall("check_calendar", JsonObject(emptyMap()))
                } else if (
                    lowered.contains("add") || lowered.contains("book") || lowered.contains("create") ||
                    lowered.contains("insert") || lowered.contains("schedule") ||
                    lowered.startsWith("remind me ")
                ) {
                    val details = CalendarCommandParser.parse(command)
                    ToolCall("open_calendar_insert", JsonObject(mapOf(
                        "title" to JsonPrimitive(details.title),
                        "start_time" to JsonPrimitive(details.startTimeIso.orEmpty()),
                        "end_time" to JsonPrimitive(details.endTimeIso.orEmpty())
                    )))
                } else null
            }

            // === COMPOUND COMMANDS (after domain-specific rules, before simple rules) ===
            // Splits on " and " into up to 3 ordered steps, each parsed independently and embedded
            // as a {tool, args} object in a single "steps" JSON array. (Previously the 3rd part was
            // parsed and discarded; it is now included.) If only one half parses, that half is
            // returned directly — the "and" was not a command separator.
            lowered.contains(" and ") &&
                domainSpecificKeywords.none { lowered.contains(it) } &&
                !(startsWithOpenVerb && explicitTarget != null) -> {
                val parts = lowered.split(" and ").map { it.trim() }.filter { it.isNotBlank() }
                val parsed = parts.mapNotNull { parse(it) }
                when {
                    parsed.size >= 2 -> {
                        val stepsArray = kotlinx.serialization.json.JsonArray(
                            parsed.take(3).map { tc ->
                                JsonObject(mapOf(
                                    "tool" to JsonPrimitive(tc.tool),
                                    "args" to tc.args
                                ))
                            }
                        )
                        ToolCall("compound", JsonObject(mapOf("steps" to stepsArray)))
                    }
                    parsed.size == 1 -> parsed.first()
                    else -> null
                }
            }

            // === SIMPLE RULES (no internal "and" usage) ===

            // Offline Document Agent. This tool opens the system picker only; filling and saving
            // a new copy stay explicit in the Document Agent UI.
            lowered.contains("fill") &&
                (lowered.contains("pdf") || lowered.contains("docx") || lowered.contains("word template")) -> {
                val format = if (lowered.contains("pdf")) "pdf" else "docx"
                ToolCall("prepare_document_fill", JsonObject(mapOf("format" to JsonPrimitive(format))))
            }

            // Secure Browser — drive the hardened WebView to an approved origin (eyes-free WS4).
            // Kept FIRST among the simple rules so a trailing task clause ("open uniassist and fill
            // the profile form") is not shadowed by the `fill` / gesture branches below. The friendly
            // names are domain-specific (see domainSpecificKeywords) so "and" is not split off; the
            // whole phrase becomes the PageAgent task. A bare "open unigurus" yields an empty task
            // (navigate-only). "secure browser" with no origin defaults to unigurus, the primary
            // property (matches SecureBrowserUiState.DEFAULT_URL).
            //
            // Match requires an open/launch verb OR an explicit "secure browser" phrase, so a
            // friendly name appearing inside an unrelated intent (e.g. "create a note about inbharat")
            // does NOT get hijacked into the browser. RuleBasedParser only PROPOSES this tool;
            // ActionExecutor re-validates the origin via ApprovedOriginPolicy, so a stale entry below
            // is rejected at execution, not trusted.
            (startsWithOpenVerb &&
                (SECURE_ORIGIN_FRIENDLY.any { lowered.contains(it.first) } || explicitTarget != null)) ||
                lowered.contains("secure browser") -> {
                val friendlyTarget = SECURE_ORIGIN_FRIENDLY.firstOrNull {
                    lowered.contains(it.first)
                }
                // A form task commonly contains an email address. Its domain is field data, not
                // the requested navigation origin. Prefer an explicit friendly site name and only
                // scan for a public URL when no friendly target was named; the negative lookbehind
                // also prevents matching a domain immediately after '@'.
                val requestedExplicitTarget = explicitTarget.takeIf { friendlyTarget == null }
                val origin = friendlyTarget?.second
                    ?: requestedExplicitTarget
                    ?: "https://unigurus.com"
                val task = lowered
                    .replace(Regex("\\bsecure browser\\b", RegexOption.IGNORE_CASE), " ")
                    .let { s ->
                        if (requestedExplicitTarget == null) s
                        else s.replace(requestedExplicitTarget, " ")
                    }
                    .let { s -> SECURE_ORIGIN_FRIENDLY.fold(s) { acc, (name, _) -> acc.replace(name, " ") } }
                    .trim()
                    .let { Regex("^(open|launch|start|use|the)\\s*", RegexOption.IGNORE_CASE).replace(it, "") }
                    .replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim()
                ToolCall("secure_browser_task", JsonObject(mapOf(
                    "origin" to JsonPrimitive(origin),
                    "task" to JsonPrimitive(task)
                )))
            }

            // Blind Aid Deactivation
            // 4D: "barriers" alone triggers deactivation only, not detection
            lowered.contains("stop blind aid") || lowered.contains("deactivate blind aid") ||
            lowered.contains("turn off blind aid") || lowered.contains("stop scanning") ||
            lowered in setOf(
                "stop blind", "stop blind mode", "stop blind view", "blind mode off",
                "blind view off", "disable blind mode", "disable blind view",
                "exit blind mode", "exit blind view", "blind mode band karo",
                "blind view band karo"
            ) ||
            lowered == "barriers" || lowered == "obstacles" ||
            ((lowered.contains("barriers") || lowered.contains("obstacles")) &&
                (lowered.contains("stop") || lowered.contains("remove") || lowered.contains("turn off") ||
                 lowered.contains("deactivate") || lowered.contains("disable") || lowered.contains("no more"))) -> {
                ToolCall("deactivate_blind_aid", JsonObject(emptyMap()))
            }

            // Blind Aid Activation — requires positive context like "detect" or "start"
            lowered.contains("start blind aid") || lowered.contains("activate blind aid") ||
            lowered in setOf(
                "start blind", "start blind mode", "start blind view",
                "enable blind mode", "enable blind view", "blind mode on", "blind view on",
                "turn on blind mode", "turn on blind view", "switch to blind mode",
                "switch to blind view", "blind mode chalu karo", "blind mode shuru karo",
                "blind mode start karo", "blind view chalu karo", "blind view shuru karo",
                "blind view start karo", "andha mode chalu karo", "netraheen mode chalu karo"
            ) ||
            lowered.contains("detect objects") || lowered.contains("what's in front of me") ||
            lowered.contains("detect barrier") ||
            ((lowered.contains("barriers") || lowered.contains("obstacles")) &&
                (lowered.contains("detect") || lowered.contains("start") || lowered.contains("activate") ||
                 lowered.contains("look for") || lowered.contains("check for") || lowered.contains("watch for"))) -> {
                ToolCall("detect_objects", JsonObject(emptyMap()))
            }

            // Screen Intelligence
            lowered.contains("read screen") || lowered.contains("what's on") || lowered.contains("what is on my screen") ||
            lowered.contains("ocr") || lowered.contains("screen text") -> {
                ToolCall("read_screen", JsonObject(emptyMap()))
            }

            // Navigation & Gestures
            lowered.contains("scroll down") || lowered.contains("page down") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("scroll_down"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("scroll up") || lowered.contains("page up") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("scroll_up"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("go back") || lowered.contains("press back") || lowered.contains("navigate back") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("go_back"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("go home") || lowered.contains("press home") || lowered.contains("go to home") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("go_home"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("open notification") || lowered.contains("show notification") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("open_notifications"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("open recent") || lowered.contains("show recent") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("open_recents"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("swipe left") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("swipe"), "target" to JsonPrimitive("left"))))
            }
            lowered.contains("swipe right") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("swipe"), "target" to JsonPrimitive("right"))))
            }
            lowered.contains("swipe up") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("swipe"), "target" to JsonPrimitive("up"))))
            }
            lowered.contains("swipe down") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("swipe"), "target" to JsonPrimitive("down"))))
            }
            // Long press on a text element — extract the target text, not coordinates
            lowered.contains("long press") || lowered.contains("long tap") -> {
                val target = Regex("(?:long press|long tap) (?:on )?(.+)", RegexOption.IGNORE_CASE)
                    .find(lowered)?.groupValues?.get(1)?.trim() ?: ""
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("long_press"), "target" to JsonPrimitive(target))))
            }
            lowered.contains("find") && lowered.contains("click") -> {
                val target = Regex("find (?:and click|and tap|then click|then tap) (.+)", RegexOption.IGNORE_CASE)
                    .find(lowered)?.groupValues?.get(1)?.trim() ?: ""
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("find_and_click"), "target" to JsonPrimitive(target))))
            }
            lowered.contains("fill") -> {
                // Accept "with", ":", or bare space as separator between hint and value
                val field = Regex("fill (?:the )?(.+?)(?:\\s+with\\s+|\\s*:\\s*|\\s+)(.+)", RegexOption.IGNORE_CASE)
                    .find(lowered)
                val hint = field?.groupValues?.get(1)?.trim() ?: ""
                val value = field?.groupValues?.get(2)?.trim() ?: ""
                ToolCall("system_control", JsonObject(mapOf(
                    "action" to JsonPrimitive("fill"),
                    "target" to JsonPrimitive(hint),
                    "value" to JsonPrimitive(value)
                )))
            }

            // Note deletion — checked BEFORE create_note so negation verbs route to delete,
            // and delete_all_notes / delete_notes become reachable offline (not only via LLM).
            (lowered.contains("delete") || lowered.contains("remove") ||
                lowered.contains("clear") || lowered.contains("erase")) &&
                (lowered.contains("note") || lowered.contains("notes")) -> {
                if (lowered.contains("all")) {
                    ToolCall("delete_all_notes", JsonObject(emptyMap()))
                } else {
                    val query = Regex("(?:about|containing|matching|with) (.+)", RegexOption.IGNORE_CASE)
                        .find(lowered)?.groupValues?.get(1)?.trim() ?: ""
                    ToolCall("delete_notes", JsonObject(mapOf("query" to JsonPrimitive(query))))
                }
            }

            // 4D: Note creation — suppress if negation verbs present ("delete note", "remove note", etc.)
            (lowered.contains("note") || lowered.contains("remember")) &&
                noteNegationVerbs.none { neg -> lowered.contains(neg) && lowered.contains("note") } -> {
                val content = extractNoteContent(command)
                ToolCall(
                    "create_note",
                    JsonObject(mapOf(
                        "title" to JsonPrimitive(content.take(40)),
                        "content" to JsonPrimitive(content),
                        "tags" to JsonPrimitive("expert")
                    ))
                )
            }

            // 4D: "open settings" MUST be checked before "open google" to prevent
            // "open google settings" from matching the wrong rule
            lowered.contains("open settings") -> {
                ToolCall("open_app", JsonObject(mapOf(
                    "app_name" to JsonPrimitive("Settings"),
                    "package_name" to JsonPrimitive("com.android.settings")
                )))
            }

            // Browser & Search
            lowered.contains("open chrome") || lowered.contains("launch browser") -> {
                ToolCall("open_chrome", JsonObject(emptyMap()))
            }

            lowered.contains("open google") || (lowered.contains("open") && lowered.contains("google")) -> {
                ToolCall("open_url", JsonObject(mapOf("url" to JsonPrimitive("https://www.google.com"))))
            }

            // Web search — open a Google search for the query in the browser. Matches
            // "search for cats", "search cats", and "google cats". Excludes anything mentioning
            // "note" so "search my notes for X" is not hijacked into a browser open (note search
            // is handled by the LLM/web_search path). URL-encodes the query.
            (lowered.contains("search for") ||
                (lowered.startsWith("search ") && !lowered.contains("note")) ||
                lowered.startsWith("google ")) && !lowered.contains("note") -> {
                val query = lowered
                    .substringAfter("search for")
                    .substringAfter("search")
                    .substringAfter("google")
                    .trim()
                val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { query }
                ToolCall(
                    "open_url",
                    JsonObject(mapOf("url" to JsonPrimitive("https://www.google.com/search?q=$encoded")))
                )
            }

            // System Control
            lowered.contains("open camera") -> {
                ToolCall("open_camera", JsonObject(emptyMap()))
            }

            // Catch-all App Opener
            lowered.startsWith("open ") || lowered.startsWith("launch ") -> {
                val appName = Regex("^(open|launch) ", RegexOption.IGNORE_CASE).replace(lowered, "").trim()
                ToolCall("open_app", JsonObject(mapOf("app_name" to JsonPrimitive(appName))))
            }

            else -> null
        }
    }

    /**
     * Offline deterministic paths for the core eyes-free actions in every language exposed by the
     * voice chooser. These run before Gemma so a blind user does not depend on a long inference for
     * navigation, camera, screen reading or app launch. Complex free-form content (message bodies,
     * email recipients and form values) still goes through the normal validated planner.
     */
    private fun parseLocalizedCoreCommand(command: String): ToolCall? {
        fun hasAny(vararg phrases: String) = phrases.any(command::contains)
        fun openApp(name: String, packageName: String) = ToolCall(
            "open_app",
            JsonObject(mapOf(
                "app_name" to JsonPrimitive(name),
                "package_name" to JsonPrimitive(packageName)
            ))
        )

        return when {
            hasAny(
                "ब्लाइंड एड बंद करो", "ब्लाइंड मोड बंद करो", "दृष्टि सहायता बंद करो",
                "ব্লাইন্ড এইড বন্ধ করো", "பிளைண்ட் எய்டை நிறுத்து",
                "బ్లైండ్ ఎయిడ్ ఆపు", "ಬ್ಲೈಂಡ್ ಏಡ್ ನಿಲ್ಲಿಸು", "ബ്ലൈൻഡ് എയ്ഡ് നിർത്തുക"
            ) -> ToolCall("deactivate_blind_aid", JsonObject(emptyMap()))

            hasAny(
                "ब्लाइंड एड चालू करो", "ब्लाइंड मोड शुरू करो", "ब्लाइंड मोड चालू करो",
                "ब्लाइंड व्यू चालू करो", "नेत्रहीन मोड चालू करो",
                "दृष्टि सहायता चालू करो", "सामने क्या है", "वस्तुओं का पता लगाओ",
                "ব্লাইন্ড এইড চালু করো", "সামনে কী আছে",
                "பிளைண்ட் எய்டை தொடங்கு", "எனக்கு முன்னால் என்ன இருக்கிறது",
                "బ్లైండ్ ఎయిడ్ ప్రారంభించు", "నా ముందు ఏముంది",
                "ಬ್ಲೈಂಡ್ ಏಡ್ ಪ್ರಾರಂಭಿಸು", "ನನ್ನ ಮುಂದೆ ಏನಿದೆ",
                "ബ്ലൈൻഡ് എയ്ഡ് തുടങ്ങുക", "എന്റെ മുന്നിൽ എന്താണ്"
            ) -> ToolCall("detect_objects", JsonObject(emptyMap()))

            hasAny(
                "स्क्रीन पढ़ो", "स्क्रीन का टेक्स्ट पढ़ो", "স্ক্রিন পড়ো",
                "திரையை படி", "ஸ்கிரீனை படி", "స్క్రీన్ చదువు", "ಪರದೆಯನ್ನು ಓದು",
                "ಸ್ಕ್ರೀನ್ ಓದು", "സ്ക്രീൻ വായിക്കുക"
            ) -> ToolCall("read_screen", JsonObject(emptyMap()))

            hasAny(
                "कैमरा खोलो", "ক্যামেরা খোলো", "கேமராவை திற", "కెమెరా తెరువు",
                "ಕ್ಯಾಮೆರಾ ತೆರೆಯಿರಿ", "ക്യാമറ തുറക്കുക"
            ) -> ToolCall("open_camera", JsonObject(emptyMap()))

            hasAny(
                "कैलेंडर खोलो", "ক্যালেন্ডার খোলো", "காலெண்டரை திற", "క్యాలెండర్ తెరువు",
                "ಕ್ಯಾಲೆಂಡರ್ ತೆರೆಯಿರಿ", "കലണ്ടർ തുറക്കുക"
            ) -> ToolCall("open_calendar", JsonObject(emptyMap()))

            command.contains("कैलेंडर") &&
                hasAny("जोड़ो", "बनाओ", "लगाओ", "रिमाइंडर") -> {
                val details = CalendarCommandParser.parse(command)
                ToolCall("open_calendar_insert", JsonObject(mapOf(
                    "title" to JsonPrimitive(details.title),
                    "start_time" to JsonPrimitive(details.startTimeIso.orEmpty()),
                    "end_time" to JsonPrimitive(details.endTimeIso.orEmpty())
                )))
            }

            hasAny("जीमेल खोलो", "ईमेल खोलो", "इनबॉक्स खोलो", "मेल खोलो") ->
                openApp("gmail", "com.google.android.gm")

            (command.contains("ईमेल") || command.contains("मेल")) &&
                hasAny("ड्राफ्ट", "लिखो", "बनाओ", "तैयार करो") -> {
                val to = Regex(
                    "([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})",
                    RegexOption.IGNORE_CASE
                ).find(command)?.value.orEmpty()
                val body = command.substringAfter("कि", "")
                    .ifBlank { command.substringAfter("लिखो", "") }
                    .trim()
                ToolCall("draft_email", JsonObject(mapOf(
                    "to" to JsonPrimitive(to),
                    "subject" to JsonPrimitive("Update"),
                    "body" to JsonPrimitive(body)
                )))
            }

            (command.contains("व्हाट्सऐप") || command.contains("व्हाट्सएप")) &&
                hasAny("मैसेज", "संदेश", "लिखो", "ड्राफ्ट") -> {
                val number = Regex("[+]?\\d{8,15}").find(command)?.value.orEmpty()
                val message = command.substringAfter("कि", "")
                    .ifBlank { command.substringAfter("मैसेज", "") }
                    .ifBlank { command.substringAfter("संदेश", "") }
                    .trim()
                ToolCall("send_whatsapp", JsonObject(mapOf(
                    "number" to JsonPrimitive(number),
                    "message" to JsonPrimitive(message)
                )))
            }

            hasAny(
                "व्हाट्सऐप खोलो", "व्हाट्सएप खोलो", "হোয়াটসঅ্যাপ খোলো",
                "வாட்ஸ்அப்பை திற", "వాట్సాప్ తెరువు", "ವಾಟ್ಸಾಪ್ ತೆರೆಯಿರಿ",
                "വാട്സ്ആപ്പ് തുറക്കുക"
            ) -> openApp("whatsapp", "com.whatsapp")

            hasAny(
                "क्रोम खोलो", "ক্রোম খোলো", "குரோமை திற", "క్రోమ్ తెరువు",
                "ಕ್ರೋಮ್ ತೆರೆಯಿರಿ", "ക്രോം തുറക്കുക"
            ) -> ToolCall("open_chrome", JsonObject(emptyMap()))

            hasAny(
                "सिक्योर ब्राउज़र खोलो", "সিকিউর ব্রাউজার খোলো",
                "பாதுகாப்பான உலாவியை திற", "సెక్యూర్ బ్రౌజర్ తెరువు",
                "ಸುರಕ್ಷಿತ ಬ್ರೌಸರ್ ತೆರೆಯಿರಿ", "സുരക്ഷിത ബ്രൗസർ തുറക്കുക"
            ) -> ToolCall(
                "secure_browser_task",
                JsonObject(mapOf(
                    "origin" to JsonPrimitive("https://unigurus.com"),
                    "task" to JsonPrimitive("")
                ))
            )

            // Common final-consonant loss from streaming English ASR.
            command in setOf("open calenda", "launch calenda") ->
                ToolCall("open_calendar", JsonObject(emptyMap()))

            else -> null
        }
    }

    /**
     * Extracts note content from commands like:
     * - "remember: pick up groceries"  → "pick up groceries"
     * - "create note buy milk"          → "buy milk"
     * - "add note: meeting at 5pm"      → "meeting at 5pm"
     * - "remember to buy groceries"     → "buy groceries" (strips grammatical "to")
     */
    private fun extractNoteContent(command: String): String {
        // If there's a colon, take everything after it as content
        val afterColon = command.substringAfterLast(":").trim()
        // Strip common prefixes like "create note", "add note", "new note", "remember"
        val stripped = afterColon
            .let { Regex("^(create|add|new)?\\s*note\\s*", RegexOption.IGNORE_CASE).replace(it, "") }
            .let { Regex("^remember\\s*(?:to\\s+)?", RegexOption.IGNORE_CASE).replace(it, "") }
            .trim()
        // If colon-based extraction yielded meaningful content, use it; otherwise parse the whole command
        return if (stripped.isNotBlank()) stripped else {
            // No colon or nothing after colon — strip prefixes from the full command
            command.trim()
                .let { Regex("^(create|add|new)?\\s*note\\s*", RegexOption.IGNORE_CASE).replace(it, "") }
                .let { Regex("^remember\\s*(?:to\\s+)?", RegexOption.IGNORE_CASE).replace(it, "") }
                .trim()
                .ifEmpty { command }
        }
    }
}
