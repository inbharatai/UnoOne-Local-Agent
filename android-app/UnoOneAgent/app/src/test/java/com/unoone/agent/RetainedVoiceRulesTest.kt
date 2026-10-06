package com.unoone.agent

import com.unoone.agent.localbrain.RuleBasedParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RetainedVoiceRulesTest {
    @Test fun globalNavigationIsExactAndDoesNotRetainBackOrScroll() {
        listOf("go home", "press home", "go to home", "open recents", "show recent apps",
            "open notifications", "show notifications").forEach { text ->
            assertTrue(text, RetainedVoiceRules.supports(RuleBasedParser.parse(text)!!))
        }
        listOf("go back", "scroll down", "scroll up").forEach { text ->
            assertFalse(text, RetainedVoiceRules.supports(RuleBasedParser.parse(text)!!))
        }
        listOf("don't go home", "I might go home tomorrow", "show notifications about homework").forEach { text ->
            assertFalse(text, RuleBasedParser.parse(text)?.let(RetainedVoiceRules::isGlobalNavigation) ?: false)
        }
        val home = RuleBasedParser.parse("go home")!!
        assertFalse(RetainedVoiceRules.supports(home.copy(args = kotlinx.serialization.json.JsonObject(
            home.args + ("target" to kotlinx.serialization.json.JsonPrimitive("another app"))))))
        assertFalse(RetainedVoiceRules.supports(home.copy(args = kotlinx.serialization.json.JsonObject(
            home.args + ("x" to kotlinx.serialization.json.JsonPrimitive(10))))))
    }

    @Test fun retainsActualLegacyDraftAndMemoTools() {
        listOf("draft WhatsApp to +919876543210 saying Hello", "record a voice memo for 8 seconds",
            "open dialer with +919876543210", "share text: Hello and Do Not Send",
            "check calendar", "create note Hello then open chrome").forEach { text ->
            val call = RuleBasedParser.parse(text)
            assertNotNull(text, call)
            assertTrue(text, RetainedVoiceRules.supports(call!!))
        }
    }
    @Test fun sharePayloadIsOpaqueAndExact() {
        val call = RuleBasedParser.parse("share text: Don't Send and Open Chrome")!!
        assertEquals("share_text", call.tool)
        assertEquals("Don't Send and Open Chrome", call.args["text"]!!.jsonPrimitive.content)
    }
    @Test fun sendEnvelopeIsNotDraftAuthority() {
        assertTrue(RetainedVoiceRules.requiresDraftClarification("please send WhatsApp to Bob"))
        assertFalse(RetainedVoiceRules.requiresDraftClarification("draft WhatsApp message send help"))
        assertFalse(RetainedVoiceRules.requiresDraftClarification("create note send help"))
    }
    @Test fun narrowAliasesDoNotInventPhoneCallsOrRecordOnMention() {
        listOf("record a voice memo for 31 seconds", "I heard record a voice memo", "don't record a voice memo",
            "call +919876543210", "pay Bob", "share all passwords").forEach {
            assertNull(it, RuleBasedParser.parse(it))
        }
    }
    @Test fun unsupportedCompoundCannotUseRetainedPath() {
        val call = RuleBasedParser.parse("create note Hello then scroll down")!!
        assertFalse(RetainedVoiceRules.supports(call))
    }
}
