package com.unoone.agent.core.voice

import java.util.Locale

/** Pure native grammar. It confers scope only; fresh native target semantics still govern effects. */
object VoiceRouteCompiler {
    private fun rx(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val stop = rx("^(?:please )?(?:stop|stop now|stop everything|stop all|stop task|stop this task|emergency stop)[.!]?$" )
    private val deviceVerb = rx("\\b(?:open|launch|go|scroll|read|tap|click|focus|select|type|write|find|search|send|submit|pay|purchase|buy|transfer|delete|install|uninstall|call|dial|book|order|unlock|enter|set|change|turn|swipe|press|navigate|enable|disable|mute|unmute|message|email|download|upload|connect|disconnect|log in|sign in|authenticate|approve|authorize|reset|reboot|record|take|start|save|copy|paste|toggle|share|use owl)\\b")
    private val question = rx("^(?:what|why|how|who|where|when|which|is|are|does|do|did|could|would|can)\\b")
    private val negation = rx("\\b(?:not|never|don['’]t|do not|can['’]t|cannot|won['’]t|without|except|avoid|instead|unless)\\b")
    private val protected = rx("\\b(?:passwords?|passcodes?|pins?|otp|one[ -]time(?: passwords?| codes?)?|verification codes?|security codes?|cvv|cvc|credentials?|secret keys?|recovery (?:codes?|phrases?)|seed phrases?|private keys?|pay(?:ment)?|payments|purchase|buy|checkout|check out|transfer|banking|bank accounts?|credit cards?|debit cards?|send|submit|post|publish|place order|confirm|approve|authorize|sign in|log in|authenticate|finali[sz]e|delete|erase|uninstall)\\b")
    private const val MAX_STEPS = 8
    private const val MAX_LITERAL = 256
    // Quotes are syntax only, not authority. Smart double quotes are accepted without normalizing payloads.
    private const val ATOM = "(?:\"[^\"]*\"|“[^”]*”|[^\"“”]+?)"
    private val open = rx("^(?:open|launch) (?:app )?($ATOM)$")
    private val simple = rx("^(go back|go home|scroll up|scroll down|read (?:this )?screen)(?: in (?:app )?($ATOM))?$")
    private val interact = rx("^(tap|click|focus|select tab) ($ATOM)(?: in (?:app )?($ATOM))?$")
    private val write = rx("^(?:type|write) ($ATOM) into ($ATOM)(?: in (?:app )?($ATOM))?$")

    fun route(
        ingress: VoiceIngress,
        environment: VoiceRouteEnvironment,
        resolver: VoiceAppResolver,
        reviewGate: VoiceReviewGate? = null
    ): VoiceRoute {
        var text = ingress.transcript.trim()
        if (stop.matches(text)) { reviewGate?.clear(); return VoiceRoute.Stop }
        if (!environment.enabled || ingress.captureGlobalGeneration != environment.globalGeneration || ingress.captureStartMono > environment.nowMono)
            return VoiceRoute.Blocked("Voice capture is disabled or stale")
        if (VoiceReviewGate.speechDecision(text) != null) return reviewGate?.consumeSpeech(ingress, environment)
            ?: VoiceRoute.Clarify("No matching live review for this reply")
        if (text.equals("yes", true)) return VoiceRoute.Clarify("Say exactly confirm after the task review")
        val owl = rx("^use owl(?: to)?\\s+").find(text)
        if (owl != null) text = text.substring(owl.range.last + 1).trim()
        val explicitDevice = text.startsWith("device:", true)
        if (explicitDevice) text = text.substringAfter(':').trim()
        val looksDevice = owl != null || explicitDevice || isDeviceRequest(text)
        if (!looksDevice) return if (environment.conversationSupported) VoiceRoute.Conversation
            else VoiceRoute.Blocked("The selected model does not support conversation")
        if (text.length > 4096 || text.any { it == '\n' || it == '\r' || it == '\u0000' }) return VoiceRoute.Clarify("Use one bounded explicit command")
        val scan = splitSteps(text) ?: return VoiceRoute.Clarify("Unbalanced quotes or ambiguous sequence; quote exact labels and values")
        if (scan.size > MAX_STEPS || scan.any { it.isBlank() }) return VoiceRoute.Clarify("Use at most $MAX_STEPS explicit steps")
        val steps = ArrayList<VoiceStep>()
        var precedingApp: AppChoice? = null
        for (part in scan) {
            // Do not treat negation or connectors inside exact quoted data as instruction syntax.
            val syntax = outsideQuotes(part) ?: return VoiceRoute.Clarify("Unbalanced quotes")
            if (negation.containsMatchIn(syntax)) return VoiceRoute.Clarify("Negated or conditional device instructions require clarification")
            val parsed = parseStep(part, precedingApp, ingress, resolver, environment)
            when (parsed) {
                is Parsed.Failure -> return parsed.route
                is Parsed.Step -> {
                    steps += parsed.step
                    if (parsed.step.operation == VoiceOperation.OPEN_APP) precedingApp = parsed.step.app
                }
            }
        }
        val purpose = NativeVoicePurpose(ingress, steps)
        return if (owl != null) VoiceRoute.NeedsOwlReview(purpose) else VoiceRoute.Native(purpose)
    }

    private fun isDeviceRequest(text: String): Boolean {
        if (!deviceVerb.containsMatchIn(text)) return false
        if (negation.find(text)?.range?.first == 0) return true
        // Informational questions remain tool-less. Polite action requests are device intent but
        // unsupported phrasing clarifies rather than falling through to the general planner.
        if (rx("^(?:can|could|would|will) you\\b").containsMatchIn(text)) return true
        return !question.containsMatchIn(text) && !rx("^(?:tell me|explain|describe|what about)\\b").containsMatchIn(text)
    }

    private sealed class Parsed {
        data class Step(val step: VoiceStep) : Parsed()
        data class Failure(val route: VoiceRoute) : Parsed()
    }
    private fun clarify(reason: String) = Parsed.Failure(VoiceRoute.Clarify(reason))
    private fun blocked() = Parsed.Failure(VoiceRoute.Blocked("Protected credentials, financial, destructive or final-send operation; complete manually"))

    private fun parseStep(text: String, precedingApp: AppChoice?, ingress: VoiceIngress, resolver: VoiceAppResolver, environment: VoiceRouteEnvironment): Parsed {
        val w = write.matchEntire(text)
        // Exclude a successfully delimited literal VALUE from intent classification, never the field.
        val safetyText = if (w != null) w.groupValues[2] + " " + w.groupValues[3] else text
        if (protected.containsMatchIn(safetyText)) return blocked()
        if (rx("\\b(?:and|or|then)\\b").containsMatchIn(outsideQuotes(text).orEmpty())) return clarify("Quote literal connectors or use an explicit then sequence")
        val o = open.matchEntire(text)
        if (o != null) {
            val name = literal(o.groupValues[1]) ?: return clarify("Specify one exact installed app")
            return when (val resolved = resolver.resolve(name)) {
                is AppResolution.Resolved -> Parsed.Step(VoiceStep(VoiceOperation.OPEN_APP, resolved.app))
                AppResolution.Ambiguous -> clarify("More than one installed app matches; choose an app")
                AppResolution.Unavailable -> clarify("Installed app is unavailable or unresolved")
            }
        }
        val s = simple.matchEntire(text)
        val i = interact.matchEntire(text)
        val operation: VoiceOperation
        val appName: String
        var label: String? = null
        var value: String? = null
        when {
            s != null -> {
                operation = when (s.groupValues[1].lowercase(Locale.ROOT)) {
                    "go back" -> VoiceOperation.BACK
                    "go home" -> VoiceOperation.HOME
                    "scroll up" -> VoiceOperation.SCROLL_UP
                    "scroll down" -> VoiceOperation.SCROLL_DOWN
                    else -> VoiceOperation.READ_SCREEN
                }
                appName = s.groupValues[2]
            }
            i != null -> {
                operation = when (i.groupValues[1].lowercase(Locale.ROOT)) {
                    "focus" -> VoiceOperation.FOCUS
                    "select tab" -> VoiceOperation.SELECT_TAB
                    else -> VoiceOperation.CLICK
                }
                label = literal(i.groupValues[2]) ?: return clarify("Specify one exact target label")
                appName = i.groupValues[3]
            }
            w != null -> {
                operation = VoiceOperation.WRITE
                value = literal(w.groupValues[1], allowEmpty = true) ?: return clarify("Quote the exact literal value")
                label = literal(w.groupValues[2]) ?: return clarify("Specify one exact field label")
                appName = w.groupValues[3]
            }
            else -> return clarify("This device intent has no bounded native grammar; specify one exact supported operation")
        }
        val app = if (appName.isNotEmpty()) {
            val name = literal(appName) ?: return clarify("Specify one exact app")
            when (val resolved = resolver.resolve(name)) {
                is AppResolution.Resolved -> resolved.app
                AppResolution.Ambiguous -> return clarify("More than one installed app matches; choose an app")
                AppResolution.Unavailable -> return clarify("Installed app is unavailable or unresolved")
            }
        } else precedingApp ?: run {
            val evidence = ingress.underlyingAppEvidence ?: return clarify("Which installed app should this operation target?")
            if (!evidence.isApplicationWindow || evidence.isOwnOverlay || evidence.windowId < 0 || evidence.observedAtMono < 0 || evidence.observedAtMono > ingress.captureStartMono ||
                environment.nowMono < evidence.observedAtMono || environment.maxEvidenceAgeMs < 0 ||
                environment.nowMono - evidence.observedAtMono > environment.maxEvidenceAgeMs)
                return clarify("Underlying application evidence is missing or ambiguous")
            when (val resolved = resolver.resolve(evidence.packageName)) {
                is AppResolution.Resolved -> if (resolved.app.packageName == evidence.packageName) resolved.app
                    else return clarify("Underlying app identity did not resolve exactly")
                else -> return clarify("Underlying app is unavailable or ambiguous")
            }
        }
        return Parsed.Step(VoiceStep(operation, app, label, value))
    }

    private fun literal(raw: String, allowEmpty: Boolean = false): String? {
        val trimmed = raw.trim()
        val quoted = (trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith('“') && trimmed.endsWith('”'))
        val value = if (quoted && trimmed.length >= 2) trimmed.substring(1, trimmed.length - 1) else trimmed
        if (value.length > MAX_LITERAL || (!allowEmpty && value.isBlank()) || value.any { it == '"' || it == '“' || it == '”' || it == '\n' || it == '\r' }) return null
        // An unquoted in/into boundary is ambiguous; quote the label/value containing it.
        if (!quoted && rx("\\b(?:and|or|then|in|into)\\b").containsMatchIn(value)) return null
        return value
    }

    /** Only explicit 'then' / 'and then' between whole supported steps is sequence syntax. */
    private fun splitSteps(text: String): List<String>? {
        val outside = outsideQuotes(text) ?: return null
        val separators = rx("\\s+(?:and\\s+)?then\\s+").findAll(outside).toList()
        val parts = ArrayList<String>()
        var start = 0
        for (separator in separators) {
            parts += text.substring(start, separator.range.first).trim()
            start = separator.range.last + 1
        }
        parts += text.substring(start).trim()
        return parts
    }

    /** Same-length masking ensures syntax matches preserve exact indices and Unicode payloads. */
    private fun outsideQuotes(text: String): String? {
        var closing: Char? = null
        val result = StringBuilder(text.length)
        for (char in text) {
            if (closing != null) {
                result.append('_')
                if (char == closing) closing = null
            } else when (char) {
                '"' -> { closing = '"'; result.append('_') }
                '“' -> { closing = '”'; result.append('_') }
                '”' -> return null
                else -> result.append(char)
            }
        }
        return if (closing == null) result.toString() else null
    }
}
