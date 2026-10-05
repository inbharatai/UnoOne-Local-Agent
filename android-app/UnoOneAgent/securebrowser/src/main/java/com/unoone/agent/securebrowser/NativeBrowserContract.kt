package com.unoone.agent.securebrowser

import kotlinx.serialization.json.*

/** Fail-closed native argument contract. No argument is inferred from page instructions. */
internal object NativeBrowserContract {
    private val keys = mapOf(
        "done" to setOf("text", "success"), "wait" to setOf("seconds"), "ask_user" to setOf("question"),
        "click_element_by_index" to setOf("index"), "input_text" to setOf("index", "text"),
        "select_dropdown_option" to setOf("index", "text"), "toggle_checkbox" to setOf("index", "checked"),
        "choose_radio" to setOf("index"), "pick_date" to setOf("index", "date"),
        "submit_form" to setOf("index", "purpose"), "upload_file" to setOf("index", "purpose"),
        "scroll" to setOf("down", "num_pages", "pixels", "index"),
        "scroll_horizontally" to setOf("right", "pixels", "index"))
    fun normalize(action: String, args: JsonObject, summary: String?): JsonObject {
        require(keys.containsKey(action) && args.keys.all { it in keys.getValue(action) }) { "Unsupported action/arguments" }
        val out = args.toMutableMap()
        fun text(key: String, max: Int = 2000): String {
            val p = args[key] as? JsonPrimitive
            require(p != null && p.isString && p.content.isNotBlank() && p.content.length <= max) { "Missing/invalid $key" }
            return p.content
        }
        fun bool(key: String) { require(args[key]?.jsonPrimitive?.booleanOrNull != null) { "Invalid $key" } }
        if (action !in setOf("done", "wait", "ask_user", "scroll", "scroll_horizontally") || "index" in args)
            require(args["index"]?.jsonPrimitive?.intOrNull?.let { it > 0 } == true) { "Missing/invalid index" }
        when (action) {
            "done" -> { text("text"); bool("success") }
            "ask_user" -> text("question", 400)
            "input_text", "select_dropdown_option" -> text("text")
            "pick_date" -> {
                val date = text("date", 10)
                require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "Date must be YYYY-MM-DD" }
                java.time.LocalDate.parse(date)
            }
            "toggle_checkbox" -> {
                if ("checked" in args) bool("checked") else {
                    val previous = summary?.let { Json.parseToJsonElement(it).jsonObject["checked"]?.jsonPrimitive?.booleanOrNull }
                    require(previous != null) { "Checkbox requires explicit desired state" }
                    out["checked"] = JsonPrimitive(!previous)
                }
            }
            "wait" -> {
                val seconds = args["seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.5
                require(seconds.isFinite() && seconds in 0.1..5.0)
                out["seconds"] = JsonPrimitive(seconds)
            }
            "scroll", "scroll_horizontally" -> {
                val direction = if (action == "scroll") "down" else "right"
                bool(direction)
                for (key in listOf("pixels", "num_pages")) if (key in args) {
                    val n = args[key]?.jsonPrimitive?.doubleOrNull
                    require(n != null && n.isFinite() && n > 0 && n <= if (key == "pixels") 5000 else 5) { "Invalid scroll bounds" }
                }
            }
        }
        return JsonObject(out)
    }
}
