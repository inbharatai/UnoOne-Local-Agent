package com.unoone.agent.localbrain

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fallback rule-based parser for commands when the local LLM is not loaded.
 * Handles 30+ command patterns including gestures, navigation, skills, and compound commands.
 */
object RuleBasedParser {

    // Domain-specific patterns that use "and" internally for their own semantics.
    // These MUST be checked BEFORE the compound handler splits on " and ".
    private val domainSpecificKeywords = listOf(
        "teach you", "create skill", "new skill",
        "email", "mail",
        "whatsapp",
        "calendar", "schedule", "events"
    )

    fun parse(command: String): ToolCall? {
        val lowered = command.lowercase().trim()

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

            // Email Drafting
            lowered.contains("email") || lowered.contains("mail") -> {
                val to = Regex("to ([\\w.]+@[\\w.]+)").find(lowered)?.groupValues?.get(1) ?: ""
                val subject = Regex("subject (.*?) (body|text|$)").find(lowered)?.groupValues?.get(1) ?: "Expert Update"
                val body = lowered.substringAfter("body", "").ifEmpty { lowered.substringAfter("text", "") }.trim()
                ToolCall(
                    "draft_email",
                    JsonObject(mapOf(
                        "to" to JsonPrimitive(to),
                        "subject" to JsonPrimitive(subject),
                        "body" to JsonPrimitive(body.ifEmpty { command })
                    ))
                )
            }

            // WhatsApp Integration
            lowered.contains("whatsapp") -> {
                val number = Regex("(?:to|at) ([+]?[\\d]{8,15})").find(lowered)?.groupValues?.get(1) ?: ""
                val message = lowered.substringAfter("whatsapp")
                    .substringAfter("message").substringAfter("saying").trim()
                    .let { Regex("^[\\d+]+\\s*").replace(it, "").trim() }
                ToolCall(
                    "send_whatsapp",
                    JsonObject(mapOf(
                        "number" to JsonPrimitive(number),
                        "message" to JsonPrimitive(message.ifEmpty { command })
                    ))
                )
            }

            // Calendar Intelligence
            lowered.contains("calendar") || lowered.contains("schedule") || lowered.contains("events") -> {
                if (lowered.contains("check") || lowered.contains("what") || lowered.contains("show") || lowered.contains("read")) {
                    ToolCall("check_calendar", JsonObject(emptyMap()))
                } else if (lowered.contains("add") || lowered.contains("book") || lowered.contains("create") || lowered.contains("insert")) {
                    val title = Regex("(open|book|add to|create|insert) calendar", RegexOption.IGNORE_CASE).replace(command, "").trim()
                    ToolCall("open_calendar_insert", JsonObject(mapOf(
                        "title" to JsonPrimitive(title.ifEmpty { "New Event" }),
                        "start_time" to JsonPrimitive(""),
                        "end_time" to JsonPrimitive("")
                    )))
                } else null
            }

            // === COMPOUND COMMANDS (after domain-specific rules, before simple rules) ===
            // "scroll down and go home" → compound. Skipped if the command matches a
            // domain-specific pattern above that uses "and" internally (e.g. skill steps).
            lowered.contains(" and ") && domainSpecificKeywords.none { lowered.contains(it) } -> {
                val parts = lowered.split(" and ", limit = 2)
                val first = parse(parts[0].trim())
                val second = parse(parts[1].trim())
                if (first != null && second != null) {
                    ToolCall(
                        "compound",
                        JsonObject(mapOf(
                            "first_tool" to JsonPrimitive(first.tool),
                            "first_args" to JsonPrimitive(first.args.toString()),
                            "second_tool" to JsonPrimitive(second.tool),
                            "second_args" to JsonPrimitive(second.args.toString())
                        ))
                    )
                } else {
                    // If only the first half parses, return it (the "and" was not a command separator)
                    first
                }
            }

            // === SIMPLE RULES (no internal "and" usage) ===

            // Blind Aid Deactivation
            lowered.contains("stop blind aid") || lowered.contains("deactivate blind aid") ||
            lowered.contains("turn off blind aid") || lowered.contains("stop scanning") ||
            ((lowered.contains("barriers") || lowered.contains("obstacles")) &&
                (lowered.contains("stop") || lowered.contains("remove") || lowered.contains("turn off") ||
                 lowered.contains("deactivate") || lowered.contains("disable") || lowered.contains("no more"))) -> {
                ToolCall("deactivate_blind_aid", JsonObject(emptyMap()))
            }

            // Blind Aid Activation
            lowered.contains("start blind aid") || lowered.contains("activate blind aid") ||
            lowered.contains("detect objects") || lowered.contains("what's in front of me") ||
            lowered.contains("detect barrier") || lowered.contains("barriers") || lowered.contains("obstacles") -> {
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

            // Note Management — robust content extraction with or without colon separator
            lowered.contains("note") || lowered.contains("remember") -> {
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

            // Browser & Search
            lowered.contains("open chrome") || lowered.contains("launch browser") -> {
                ToolCall("open_chrome", JsonObject(emptyMap()))
            }

            lowered.contains("open google") || (lowered.contains("open") && lowered.contains("google")) -> {
                ToolCall("open_url", JsonObject(mapOf("url" to JsonPrimitive("https://www.google.com"))))
            }

            // System Control
            lowered.contains("open settings") -> {
                ToolCall("open_app", JsonObject(mapOf(
                    "app_name" to JsonPrimitive("Settings"),
                    "package_name" to JsonPrimitive("com.android.settings")
                )))
            }

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