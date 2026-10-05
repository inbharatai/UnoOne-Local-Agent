package com.unoone.agent

import com.unoone.agent.core.device.*

/** Native intent descriptors, never constructed from model output or screen text. */
sealed class NativeDeviceGoal {
    data class OpenApp(val packageName: String) : NativeDeviceGoal() {
        init { require(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(packageName)) }
    }
    data class Find(val packageName: String, val exactText: String) : NativeDeviceGoal()
    data class Click(val packageName: String, val exactText: String) : NativeDeviceGoal()
    data class SetField(val packageName: String, val resourceId: String, val text: String) : NativeDeviceGoal()
    data class Current(val command: String, val exactText: String = "") : NativeDeviceGoal()
    data class ReadScreen(val packageName: String) : NativeDeviceGoal()
    data class Back(val packageName: String) : NativeDeviceGoal()
    data class Scroll(val packageName: String, val direction: ScrollDirection) : NativeDeviceGoal()
    data class Sequence(val goals: List<NativeDeviceGoal>) : NativeDeviceGoal()
    data class NeedsUser(val reason: String) : NativeDeviceGoal()
}

object NativeDeviceCommands {
    private val apps = mapOf(
        "settings" to "com.android.settings", "chrome" to "com.android.chrome",
        "youtube" to "com.google.android.youtube", "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "whatsapp" to "com.whatsapp"
    )
    /** Null leaves normal CHAT/rules untouched. Explicit device requests never fall into CHAT. */
    fun parse(text: String, resolveApp: ((String) -> String?)? = null): NativeDeviceGoal? {
        if (!text.trim().startsWith("device:", true)) return parseSingle(text, resolveApp)
        val command = text.trim().substringAfter(':').trim()
        val parts = mutableListOf<String>()
        var quoted = false
        var start = 0
        var i = 0
        while (i < command.length) {
            if (command[i] == '"') quoted = !quoted
            if (!quoted && command.regionMatches(i, " then ", 0, 6, true)) {
                parts += command.substring(start, i).trim()
                i += 6; start = i; continue
            }
            i++
        }
        if (quoted) return NativeDeviceGoal.NeedsUser("Unclosed quote")
        parts += command.substring(start).trim()
        if (parts.size > 24 || parts.any { it.isEmpty() }) return NativeDeviceGoal.NeedsUser("Invalid sequence")
        val goals = parts.map { parseSingle("device: $it", resolveApp) }
        if (goals.any { it == null || it is NativeDeviceGoal.NeedsUser })
            return NativeDeviceGoal.NeedsUser("Every step must have explicit app scope and a supported native goal")
        return if (goals.size == 1) goals.single() else NativeDeviceGoal.Sequence(goals.filterNotNull())
    }

    private fun payload(raw: String): String? {
        if (raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2) {
            val value = raw.substring(1, raw.length - 1)
            return value.takeIf { it.isNotEmpty() && !it.contains('"') && it.length <= 256 }
        }
        return raw.takeIf { it.isNotEmpty() && it.length <= 256 && !it.contains('"') &&
            !Regex("(?i)\\b(?:and|then)\\b").containsMatchIn(it) }
    }
    private fun parseSingle(text: String, resolveApp: ((String) -> String?)? = null): NativeDeviceGoal? {
        val trimmed = text.trim()
        val explicit = trimmed.startsWith("device:", ignoreCase = true)
        val command = if (explicit) trimmed.substringAfter(':').trim() else trimmed
        when (command.lowercase()) {
            "read screen" -> return NativeDeviceGoal.Current("read")
            "go back" -> return NativeDeviceGoal.Current("back")
            "scroll down" -> return NativeDeviceGoal.Current("down")
            "scroll up" -> return NativeDeviceGoal.Current("up")
        }
        if (command.startsWith("draft:", true)) return NativeDeviceGoal.NeedsUser("Draft composition requires a reviewed recipient and composer; no text entered or sent. Complete manually.")
        val currentClick = Regex("(?is)^click (.+)$").matchEntire(command)
        if (currentClick != null && !command.contains(" in ", true)) return payload(currentClick.groupValues[1])?.let {
            NativeDeviceGoal.Current("click", it)
        } ?: NativeDeviceGoal.NeedsUser("Quote one exact target")
        val chat = Regex("(?is)^find whatsapp chat with (.+)$").matchEntire(command)
        if (chat != null) {
            val exact = payload(chat.groupValues[1]) ?: return NativeDeviceGoal.NeedsUser("Specify one exact visible name")
            val app = if (resolveApp == null) apps["whatsapp"] else resolveApp("whatsapp")
            return app?.let { NativeDeviceGoal.Find(it, exact) } ?: NativeDeviceGoal.NeedsUser("WhatsApp unavailable")
        }
        val match = Regex("(?i)^(?:open|launch) (?:app )?([a-z0-9_.]+)$").matchEntire(command)
        val name = match?.groupValues?.get(1)
        val pkg = name?.let { if (resolveApp != null) resolveApp(it) else apps[it.lowercase()] }
        if (pkg != null) return NativeDeviceGoal.OpenApp(pkg)
        if (explicit && name != null && name.contains('.') &&
            Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(name)) {
            return if (resolveApp == null) NativeDeviceGoal.OpenApp(name) else NativeDeviceGoal.NeedsUser("App unavailable or ambiguous")
        }
        if (explicit) {
            val scoped = Regex("(?is)^(find|click) (.+) in (.+)$").matchEntire(command)
            if (scoped != null) {
                val app = resolveApp?.invoke(scoped.groupValues[3])
                    ?: return NativeDeviceGoal.NeedsUser("Installed app resolution required")
                val label = payload(scoped.groupValues[2]) ?: return NativeDeviceGoal.NeedsUser("Quote exact text; ambiguous payload")
                if (label.length > 256) return NativeDeviceGoal.NeedsUser("Text exceeds native bounds")
                return if (scoped.groupValues[1].equals("find", true)) NativeDeviceGoal.Find(app, label)
                    else NativeDeviceGoal.Click(app, label)
            }
            val edit = Regex("(?is)^set ([A-Za-z0-9_.]+:id/[A-Za-z0-9_]+) to (.+)$").matchEntire(command)
            if (edit != null) {
                val id = edit.groupValues[1]
                val app = resolveApp?.invoke(id.substringBefore(':'))
                    ?: return NativeDeviceGoal.NeedsUser("Installed field package required")
                if (edit.groupValues[2].length > 256) return NativeDeviceGoal.NeedsUser("Value exceeds observable bounds")
                return NativeDeviceGoal.SetField(app, id, payload(edit.groupValues[2]) ?: return NativeDeviceGoal.NeedsUser("Quote exact value"))
            }
        }
        return if (explicit) NativeDeviceGoal.NeedsUser(
            "This device workflow has no reviewed native postcondition. Use device: open <package>, or complete it manually."
        ) else null
    }
}

/** Launch exactly once, then observe settling; dispatch never implies completion. */
class NativeOpenAppBrain(private val packageName: String) : UnoBrain {
    override val capabilities = BrainCapabilities(chat = false, devicePlanning = true)
    override suspend fun plan(request: DevicePlanRequest): DeviceAction =
        if (request.step == 0) DeviceAction.OpenApp(packageName) else DeviceAction.Wait(350)
    override suspend fun chat(message: String): String = error("Chat unavailable in native planner")
    override suspend fun interpretScreen(state: PerceptionState) = ScreenInterpretation("Native foreground verification only")
    override suspend fun groundTarget(description: String, state: PerceptionState) = TargetGrounding()
    override suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState) =
        OutcomeAdvice(false, "Only the native predicate can establish completion")
}

/** Source-reviewed candidates, not device-qualified. No unknown field receives semantic authority. */
object NativeReviewedTargets {
    fun semantic(pkg: String, id: String, cls: String): TargetSemantic {
        val search = id == "android:id/search_src_text" ||
            (pkg == "com.google.android.gm" && id == "$pkg:id/search_view") ||
            (pkg == "com.whatsapp" && id == "$pkg:id/search_src_text")
        val reviewed = pkg in setOf("com.whatsapp", "com.google.android.gm", "com.android.settings", "com.android.chrome")
        if (!reviewed) return TargetSemantic.UNKNOWN
        if (search && cls == "android.widget.EditText") return TargetSemantic.FORM_FIELD
        if ((id == "android:id/search_button" || (pkg == "com.whatsapp" && id == "$pkg:id/menuitem_search")) &&
            cls in setOf("android.widget.ImageButton", "android.widget.TextView")) return TargetSemantic.NAVIGATION
        if (id == "android:id/list" && cls == "android.widget.ListView") return TargetSemantic.NAVIGATION
        return TargetSemantic.UNKNOWN
    }
}
