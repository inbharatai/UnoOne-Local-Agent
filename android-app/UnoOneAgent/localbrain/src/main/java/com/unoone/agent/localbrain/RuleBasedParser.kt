package com.unoone.agent.localbrain

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fallback rule-based parser for commands when the local LLM is not loaded.
 * Handles 30+ command patterns including gestures, navigation, skills, and compound commands.
 */
object RuleBasedParser {

    fun parse(command: String): ToolCall? {
        val lowered = command.lowercase().trim()

        return when {
            // Skill Building
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

            // Offline Object & Obstacle Detection (Vision) - Deactivation (Checked first to prevent substring collision with "activate")
            lowered.contains("stop blind aid") || lowered.contains("deactivate blind aid") ||
            lowered.contains("turn off blind aid") || lowered.contains("stop scanning") -> {
                ToolCall("deactivate_blind_aid", JsonObject(emptyMap()))
            }

            // Offline Object & Obstacle Detection (Vision) - Activation
            lowered.contains("start blind aid") || lowered.contains("activate blind aid") ||
            lowered.contains("detect objects") || lowered.contains("what's in front of me") ||
            lowered.contains("barriers") || lowered.contains("obstacles") || lowered.contains("detect barrier") -> {
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
            lowered.contains("long press") || lowered.contains("long tap") -> {
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("long_press"), "target" to JsonPrimitive(""))))
            }
            lowered.contains("find") && lowered.contains("click") -> {
                val target = Regex("find (?:and click|and tap|then click|then tap) (.+)", RegexOption.IGNORE_CASE)
                    .find(lowered)?.groupValues?.get(1)?.trim() ?: ""
                ToolCall("system_control", JsonObject(mapOf("action" to JsonPrimitive("find_and_click"), "target" to JsonPrimitive(target))))
            }
            lowered.contains("fill") -> {
                val field = Regex("fill (?:the )?(.+?)(?: with|:) (.+)", RegexOption.IGNORE_CASE)
                    .find(lowered)
                val hint = field?.groupValues?.get(1)?.trim() ?: ""
                val value = field?.groupValues?.get(2)?.trim() ?: ""
                ToolCall("system_control", JsonObject(mapOf(
                    "action" to JsonPrimitive("fill"),
                    "target" to JsonPrimitive(hint),
                    "value" to JsonPrimitive(value)
                )))
            }

            // Note Management
            lowered.contains("note") || lowered.contains("remember") -> {
                val content = command.substringAfterLast(":").trim()
                    .let { Regex("^(create|add|new)?\\s*note", RegexOption.IGNORE_CASE).replace(it, "") }
                    .let { Regex("^remember", RegexOption.IGNORE_CASE).replace(it, "") }
                    .trim()
                    .ifEmpty { command }
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

            // Compound commands: "open chrome and search for weather"
            lowered.contains(" and ") -> {
                val parts = lowered.split(" and ", limit = 2)
                val first = parse(parts[0].trim())
                first
            }

            // Catch-all App Opener
            lowered.startsWith("open ") || lowered.startsWith("launch ") -> {
                val appName = Regex("^(open|launch) ", RegexOption.IGNORE_CASE).replace(lowered, "").trim()
                ToolCall("open_app", JsonObject(mapOf("app_name" to JsonPrimitive(appName))))
            }

            else -> null
        }
    }
}
