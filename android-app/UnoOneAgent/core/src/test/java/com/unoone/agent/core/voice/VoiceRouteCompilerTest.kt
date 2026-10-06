package com.unoone.agent.core.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceRouteCompilerTest {
    private val maps = AppChoice("com.example.maps", "Maps")
    private val notes = AppChoice("com.example.notes", "Notes")
    private val resolver = VoiceAppResolver { name -> when (name.lowercase(java.util.Locale.ROOT)) {
        "maps", "com.example.maps", "地図" -> AppResolution.Resolved(maps)
        "notes", "com.example.notes" -> AppResolution.Resolved(notes)
        "duplicate" -> AppResolution.Ambiguous
        else -> AppResolution.Unavailable
    } }
    private val evidence = UnderlyingAppEvidence(maps.packageName, 7, 80)
    private val env = VoiceRouteEnvironment(true, 4, 500)
    private fun ticket(text: String) = VoiceIngress("request-a", text, 4, 100, evidence)
    private fun route(text: String) = VoiceRouteCompiler.route(ticket(text), env, resolver)
    private fun native(text: String) = (route(text) as VoiceRoute.Native).purpose

    @Test fun staleAtAdmissionClarifiesButExplicitOpenDoesNotNeedScope() {
        val later = env.copy(nowMono = 20_000)
        assertTrue(VoiceRouteCompiler.route(ticket("scroll down"), later, resolver) is VoiceRoute.Clarify)
        assertTrue(VoiceRouteCompiler.route(ticket("open Notes"), later, resolver) is VoiceRoute.Native)
    }
    @Test fun freshNewCaptureDoesNotRetargetExistingPurpose() {
        val old = native("scroll down")
        val fresh = ticket("scroll down").copy(captureStartMono = 20_000,
            underlyingAppEvidence = UnderlyingAppEvidence(notes.packageName, 9, 20_000))
        val next = VoiceRouteCompiler.route(fresh, env.copy(nowMono = 20_100), resolver) as VoiceRoute.Native
        assertEquals(notes, next.purpose.steps.single().app)
        assertEquals(maps, old.steps.single().app)
        assertEquals(7, old.underlyingAppEvidence!!.windowId)
    }

    @Test fun commonSpokenFormsAreNativeWithoutModelDependency() {
        val forms = mapOf("open app Maps" to VoiceOperation.OPEN_APP, "go back" to VoiceOperation.BACK,
            "go home" to VoiceOperation.HOME, "scroll up" to VoiceOperation.SCROLL_UP,
            "scroll down" to VoiceOperation.SCROLL_DOWN, "read this screen" to VoiceOperation.READ_SCREEN,
            "tap Search" to VoiceOperation.CLICK, "click Search" to VoiceOperation.CLICK,
            "focus Search" to VoiceOperation.FOCUS, "select tab Saved" to VoiceOperation.SELECT_TAB,
            "type Hello into Search" to VoiceOperation.WRITE)
        forms.forEach { (text, operation) -> assertEquals(text, operation, native(text).steps.single().operation) }
    }
    @Test fun caseAndUnicodeValuesAreNotNormalized() {
        val step = native("TyPe “İstanbul 👩🏽‍💻 e\u0301 日本語” into “Поиск” in app 地図").steps.single()
        assertEquals("İstanbul 👩🏽‍💻 e\u0301 日本語", step.exactValue)
        assertEquals("Поиск", step.exactLabel)
        assertEquals(maps, step.app)
    }
    @Test fun quotedConnectorsAndNegationsAreLiteralData() {
        val purpose = native("write \"Don't change A and then B\" into \"Search and then Results\"")
        assertEquals(1, purpose.steps.size)
        assertEquals("Don't change A and then B", purpose.steps.single().exactValue)
        assertEquals("Search and then Results", purpose.steps.single().exactLabel)
    }
    @Test fun explicitSequenceBindsFollowingStepsToOpenedApp() {
        val p = native("open Notes and then write \"A then B\" into Search then tap Search")
        assertEquals(3, p.steps.size)
        assertTrue(p.steps.all { it.app == notes })
        assertEquals("A then B", p.steps[1].exactValue)
    }
    @Test fun optionalAppScopeDoesNotLaunchApp() {
        val step = native("click Search in Notes").steps.single()
        assertEquals(notes, step.app)
        assertEquals(VoiceOperation.CLICK, step.operation)
    }
    @Test fun unknownAndAmbiguousAppsClarify() {
        assertTrue(route("open Unknown") is VoiceRoute.Clarify)
        assertTrue(route("open Duplicate") is VoiceRoute.Clarify)
    }
    @Test fun ambiguousLabelsAndSequencesFailClosed() {
        listOf("tap A and B", "open Maps or Notes", "write A into B into C", "tap \"Search", "tap A then", "open Maps then do whatever", "tap Search in Notes in Maps").forEach {
            assertTrue("$it did not clarify", route(it) is VoiceRoute.Clarify)
        }
    }
    @Test fun boundedSequenceAndPayloadLimits() {
        assertTrue(route((1..9).joinToString(" then ") { "go back" }) is VoiceRoute.Clarify)
        assertTrue(route("tap \"${"x".repeat(257)}\"") is VoiceRoute.Clarify)
        assertTrue(route("type \"\" into Search") is VoiceRoute.Native)
        assertTrue(route("tap \"   \"") is VoiceRoute.Clarify)
    }
    @Test fun negatedConditionalCommandsNeverExecute() {
        listOf("don't open Maps", "do not tap Search", "never scroll down", "open Maps unless busy", "click Search instead", "don’t go home").forEach {
            assertTrue(it, route(it) is VoiceRoute.Clarify)
        }
    }
    @Test fun protectedOperationsNeverFallBackEvenExplicitOwl() {
        listOf("tap Send", "click Pay now", "focus Password", "write 1234 into PIN", "enter OTP", "transfer money", "delete account", "use owl to click Submit", "use owl buy shoes", "type \"abc\" into \"Security code\"").forEach {
            assertTrue(it, route(it) is VoiceRoute.Blocked)
        }
    }
    @Test fun literalWriteIsDataNotFinalSendAuthority() {
        val step = native("type \"send then pay\" into Search").steps.single()
        assertEquals("send then pay", step.exactValue)
        assertEquals(VoiceOperation.WRITE, step.operation)
    }
    @Test fun explicitOwlPreservesKnownNativePurposeOnly() {
        val n = native("click Search")
        val o = (route("use owl to click Search") as VoiceRoute.NeedsOwlReview).purpose
        assertEquals(n.steps, o.steps)
        assertEquals(n.digest, o.digest)
        assertTrue(route("use owl organize my apps") is VoiceRoute.Clarify)
    }
    @Test fun unknownDeviceRequestsDoNotReachPlanner() {
        listOf("please open my favourite app", "swipe left", "can you order me lunch", "device: surprise me", "set volume to ten", "find my photos").forEach {
            assertFalse(it, route(it) is VoiceRoute.Conversation)
        }
    }
    @Test fun ordinaryQuestionsAreToollessAndCapabilityGated() {
        listOf("What is a password?", "How do I open Maps?", "Why is the sky blue?", "Explain how to pay a bill", "Hello").forEach {
            assertTrue(it, route(it) is VoiceRoute.Conversation)
        }
        assertTrue(VoiceRouteCompiler.route(ticket("Hello"), env.copy(conversationSupported = false), resolver) is VoiceRoute.Blocked)
    }
    @Test fun evidenceCannotBeOverlayFutureOrResolvedToDifferentApp() {
        listOf(evidence.copy(isOwnOverlay = true), evidence.copy(isApplicationWindow = false), evidence.copy(observedAtMono = 101), evidence.copy(windowId = -1)).forEach {
            assertTrue(VoiceRouteCompiler.route(ticket("go back").copy(underlyingAppEvidence = it), env, resolver) is VoiceRoute.Clarify)
        }
        assertTrue(VoiceRouteCompiler.route(ticket("go back").copy(underlyingAppEvidence = null), env, resolver) is VoiceRoute.Clarify)
        val wrong = VoiceAppResolver { AppResolution.Resolved(notes) }
        assertTrue(VoiceRouteCompiler.route(ticket("go back"), env, wrong) is VoiceRoute.Clarify)
    }
    @Test fun explicitOpenDoesNotNeedForegroundEvidence() {
        assertTrue(VoiceRouteCompiler.route(ticket("open Maps").copy(underlyingAppEvidence = null), env, resolver) is VoiceRoute.Native)
    }
    @Test fun stopWinsOverDisabledAndStaleAdmission() {
        assertEquals(VoiceRoute.Stop, VoiceRouteCompiler.route(ticket("Stop!").copy(captureGlobalGeneration = 0), env.copy(enabled = false), resolver))
        assertTrue(VoiceRouteCompiler.route(ticket("open Maps").copy(captureGlobalGeneration = 0), env, resolver) is VoiceRoute.Blocked)
        assertTrue(VoiceRouteCompiler.route(ticket("open Maps").copy(captureStartMono = 501), env, resolver) is VoiceRoute.Blocked)
    }
    @Test fun exactLabelsDoNotGetRegexAuthority() {
        assertEquals(".* [A-Z]+ $1", native("tap \".* [A-Z]+ $1\"").steps.single().exactLabel)
    }
    @Test fun immutableScopeAndDigestBindExactValues() {
        val p = native("type \"Case\" into Search")
        assertNotEquals(p.digest, native("type \"case\" into Search").digest)
        try { (p.steps as MutableList<VoiceStep>).clear(); fail("Mutable scope") } catch (_: UnsupportedOperationException) { }
    }
}
