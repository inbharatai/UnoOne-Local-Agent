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
    /** Null package means bind to the foreground app only if it is already in core TaskScope. */
    data class Interact(val interaction: ReviewedInteraction, val packageName: String? = null) : NativeDeviceGoal()
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

    /** Admission follows explicit opens, never granting the launcher merely for a later current step. */
    internal fun scopePackages(goal: NativeDeviceGoal, initialForeground: () -> String?): Set<String> {
        val packages = mutableSetOf<String>()
        val initial by lazy(initialForeground)
        var precedingOpen: String? = null
        fun visit(step: NativeDeviceGoal) {
            when (step) {
                is NativeDeviceGoal.Sequence -> step.goals.forEach(::visit)
                is NativeDeviceGoal.OpenApp -> { precedingOpen = step.packageName; packages.add(step.packageName) }
                is NativeDeviceGoal.Current -> (precedingOpen ?: initial)?.let(packages::add)
                is NativeDeviceGoal.Interact -> (step.packageName ?: precedingOpen ?: initial)?.let(packages::add)
                is NativeDeviceGoal.Find -> packages.add(step.packageName)
                is NativeDeviceGoal.Click -> packages.add(step.packageName)
                is NativeDeviceGoal.SetField -> packages.add(step.packageName)
                is NativeDeviceGoal.ReadScreen -> packages.add(step.packageName)
                is NativeDeviceGoal.Back -> packages.add(step.packageName)
                is NativeDeviceGoal.Scroll -> packages.add(step.packageName)
                is NativeDeviceGoal.NeedsUser -> Unit
            }
        }
        visit(goal)
        return packages
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
        // Strict quoted forms are explicit user reviews of operation + target + exact value.
        if (explicit) {
            val write = Regex("(?is)^write \"([^\"]{0,256})\" into \"([^\"]{1,256})\"(?: in ([^\"]+))?$").matchEntire(command)
            if (write != null && write.groupValues[2].isBlank()) return NativeDeviceGoal.NeedsUser("Empty field label")
            if (write != null) {
                val app = if (write.groupValues[3].isEmpty()) null else
                    resolveApp?.invoke(write.groupValues[3])
                        ?: return NativeDeviceGoal.NeedsUser("App unavailable or ambiguous")
                return NativeDeviceGoal.Interact(ReviewedInteraction(
                    NativeTargetSelector(write.groupValues[2]), ReviewedOperation.WRITE, write.groupValues[1]), app)
            }
            val interact = Regex("(?is)^(click|focus|select tab) \"([^\"]{1,256})\"(?: result ([1-9][0-9]{0,3}))?(?: in ([^\"]+))?$").matchEntire(command)
            if (interact != null && interact.groupValues[2].isBlank()) return NativeDeviceGoal.NeedsUser("Empty target label")
            if (interact != null) {
                val app = if (interact.groupValues[4].isEmpty()) null else
                    resolveApp?.invoke(interact.groupValues[4])
                        ?: return NativeDeviceGoal.NeedsUser("App unavailable or ambiguous")
                return NativeDeviceGoal.Interact(ReviewedInteraction(
                NativeTargetSelector(interact.groupValues[2], interact.groupValues[3].toIntOrNull()),
                when (interact.groupValues[1].lowercase()) {
                    "focus" -> ReviewedOperation.FOCUS
                    "select tab" -> ReviewedOperation.SELECT_TAB
                    else -> ReviewedOperation.CLICK
                }), app)
            }
        }
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
        // Only this app's exact owned practice IDs and framework classes receive authority.
        if (pkg == "com.unoone.agent") {
            if (id == "$pkg:id/owl_practice_search_field" && cls == "android.widget.EditText") return TargetSemantic.FORM_FIELD
            if (id == "$pkg:id/owl_practice_search_button" && cls == "android.widget.TextView") return TargetSemantic.NAVIGATION
        }
        val search = id == "android:id/search_src_text" ||
            (pkg == "com.google.android.gm" && id == "$pkg:id/search_view") ||
            (pkg == "com.whatsapp" && id == "$pkg:id/search_src_text")
        // Framework resource/class contracts apply in any scoped installed app, not four packages.
        if (search && cls == "android.widget.EditText") return TargetSemantic.FORM_FIELD
        if ((id == "android:id/search_button" || (pkg == "com.whatsapp" && id == "$pkg:id/menuitem_search")) &&
            cls in setOf("android.widget.ImageButton", "android.widget.TextView")) return TargetSemantic.NAVIGATION
        if (id == "android:id/list" && cls == "android.widget.ListView") return TargetSemantic.NAVIGATION
        return TargetSemantic.UNKNOWN
    }
}
