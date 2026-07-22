package com.unoone.agent.localbrain

import com.google.ai.edge.litertlm.OpenApiTool
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.ToolParamType
import com.unoone.agent.core.model.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes only command-relevant tools to E4B. Registering all 29 reflection schemas on every turn
 * consumed roughly 3,000 input tokens and made the qualified 2,048-token phone context reject even
 * "Open Chrome" before inference. Native canonical validation and SafetyGuard still cover every
 * proposal; routing only removes irrelevant choices from this model turn.
 */
internal object PlannerToolRouter {
    private val registry = CanonicalToolRegistry

    fun schemasFor(command: String): List<ToolSchema> {
        val text = command.lowercase().trim()
        val selected = linkedSetOf(registry.speak_response)

        // Secrets, payments and prohibited automation get clarification/refusal only. This also
        // prevents a screen-control schema from being exposed to an "enter my OTP" request.
        if (containsAny(text, "password", "passcode", "otp", "pin code", "card number", "cvv",
                "upi", "payment", "pay ", "transfer ", "captcha", "legal terms")) {
            return selected.toList()
        }

        when {
            containsAny(text, "blind", "obstacle", "ब्लाइंड", "अंध", "बाधा") -> {
                if (containsAny(text, "off", "stop", "deactivate", "band", "बंद", "बन्द")) {
                    selected.add(registry.deactivate_blind_aid)
                } else {
                    selected.add(registry.detect_objects)
                }
            }

            containsAny(text, "screen", "ocr", "on display", "स्क्रीन", "स्क्रीन पर") ->
                selected.addAll(listOf(registry.read_screen, registry.ocr_screen, registry.describe_scene))

            containsAny(text, "calendar", "appointment", "schedule", "reminder", "event",
                "कैलेंडर", "अपॉइंटमेंट", "रिमाइंडर") -> when {
                ISO_TIME.containsMatchIn(command) -> selected.add(registry.open_calendar_insert)
                containsAny(text, "what is on", "what's on", "check", "today", "क्या है") ->
                    selected.add(registry.check_calendar)
                containsAny(text, "open calendar", "calendar खोलो", "कैलेंडर खोलो", "कैलेंडर खोलें") ->
                    selected.add(registry.open_calendar)
                // Natural-language dates are resolved by the deterministic calendar parser before
                // E4B. If one reaches this lane without a verified ISO value, ask instead of guess.
            }

            containsAny(text, "whatsapp", "व्हाट्सऐप") -> when {
                isPlainOpenRequest(text) -> selected.add(registry.open_app)
                PHONE_NUMBER.containsMatchIn(command) -> selected.add(registry.send_whatsapp)
                else -> Unit
            }

            containsAny(text, "email", "e-mail", "mail ", "gmail", "ईमेल", "मेल ") -> when {
                isPlainOpenRequest(text) -> selected.add(registry.open_app)
                EMAIL_ADDRESS.containsMatchIn(command) -> selected.add(registry.draft_email)
                else -> Unit
            }

            containsAny(text, "note", "notes", "summarize", "shopping naam", "नोट", "सारांश",
                "wipe everything") -> selected.addAll(listOf(
                registry.create_note, registry.search_notes, registry.summarize_text,
                registry.delete_notes, registry.delete_all_notes, registry.export_data
            ))

            containsAny(text, "pdf", "docx", "document", "form", "दस्तावेज", "फ़ॉर्म") ->
                selected.addAll(listOf(registry.prepare_document_fill, registry.secure_browser_task))

            containsAny(text, "chrome", "website", "url", "browser", "web ", "http://", "https://",
                "वेबसाइट", "ब्राउज़र") -> selected.addAll(listOf(
                registry.open_chrome, registry.open_url, registry.web_search, registry.secure_browser_task
            ))

            containsAny(text, "camera", "photo", "कैमरा", "फोटो") -> selected.add(registry.open_camera)
            containsAny(text, "dial", "call", "phone number", "डायल", "कॉल") -> selected.add(registry.open_dialer)
            containsAny(text, "record my voice", "voice memo", "record audio", "आवाज़ रिकॉर्ड") ->
                selected.add(registry.voice_recording)
            containsAny(text, "create skill", "new skill", "कौशल") -> selected.add(registry.create_skill)
            containsAny(text, "share ", "share this", "साझा") -> selected.add(registry.share_text)
            containsAny(text, "click", "tap", "type", "fill", "scroll", "go back", "go home",
                "notifications", "recents", "क्लिक", "टाइप", "स्क्रॉल") -> selected.add(registry.system_control)
            isAppOpenRequest(text) -> selected.addAll(listOf(registry.open_app, registry.open_chrome))
        }
        return selected.toList()
    }

    private fun isAppOpenRequest(text: String): Boolean =
        text.length > 4 && containsAny(text, "open ", "launch ", "start app", "खोलो", "खोलें")

    private fun isPlainOpenRequest(text: String): Boolean =
        containsAny(text, "open", "launch", "खोलो", "खोलें") &&
            !containsAny(text, "message", "saying", "draft", "subject", "body", "लिखो", "भेज")

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any(text::contains)

    fun speechOnly(command: String): Boolean =
        schemasFor(command).map { it.name } == listOf("speak_response")

    fun safeFallbackText(command: String): String =
        if (DEVANAGARI.containsMatchIn(command)) {
            "कृपया आवश्यक जानकारी दें या अपना अनुरोध स्पष्ट करें।"
        } else {
            "Please provide the missing information or rephrase your request."
        }

    private val EMAIL_ADDRESS = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val PHONE_NUMBER = Regex("(?<!\\w)\\+?[0-9][0-9 ()-]{5,}[0-9](?!\\w)")
    private val ISO_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
}

/** Minimal OpenAPI schema used only for manual tool proposals; execution remains native. */
internal class CanonicalOpenApiTool(private val schema: ToolSchema) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = Json.encodeToString(buildJsonObject {
        put("name", schema.name)
        put("description", TOOL_DESCRIPTIONS[schema.name] ?: schema.name.replace('_', ' '))
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                schema.params.forEach { param ->
                    put(param.name, buildJsonObject {
                        put("type", jsonType(param.type))
                        if (param.type == ToolParamType.STRING_LIST) {
                            put("items", buildJsonObject { put("type", "string") })
                        }
                    })
                }
            })
            put("required", buildJsonArray {
                schema.requiredParams.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
            })
        })
    })

    override fun execute(paramsJsonString: String): String =
        "Manual tool calling is enabled; native execution handles ${schema.name}."

    private fun jsonType(type: ToolParamType): String = when (type) {
        ToolParamType.STRING -> "string"
        ToolParamType.INT -> "integer"
        ToolParamType.BOOLEAN -> "boolean"
        ToolParamType.FLOAT, ToolParamType.DOUBLE -> "number"
        ToolParamType.STRING_LIST -> "array"
    }

    private companion object {
        val TOOL_DESCRIPTIONS = mapOf(
            "speak_response" to "Speak briefly or ask for missing information",
            "open_app" to "Open a named installed app",
            "send_whatsapp" to "Prepare a WhatsApp draft; never send",
            "draft_email" to "Prepare an email draft; never send",
            "open_calendar" to "Open Calendar without adding an event",
            "open_calendar_insert" to "Open a reviewable new-event form",
            "check_calendar" to "Read today's calendar events",
            "read_screen" to "Read current accessibility text",
            "ocr_screen" to "OCR the current screen",
            "describe_scene" to "Describe screen evidence",
            "detect_objects" to "Start Blind Aid object detection",
            "deactivate_blind_aid" to "Stop Blind Aid",
            "secure_browser_task" to "Open Secure Browser for one bounded page task",
            "prepare_document_fill" to "Open offline PDF or DOCX fill workflow",
            "system_control" to "Perform one visible accessibility action"
        )
    }
}
