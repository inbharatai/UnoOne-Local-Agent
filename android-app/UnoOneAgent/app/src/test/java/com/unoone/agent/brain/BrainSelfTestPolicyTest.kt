package com.unoone.agent.brain

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSelfTestPolicyTest {
    @Test fun `summary requires exact tool and exact sentence`() {
        assertTrue(BrainSelfTestPolicy.validate("summarize", call("summarize_text", "text", SENTENCE)))
        assertFalse(BrainSelfTestPolicy.validate("summarize", call("speak_response", "text", SENTENCE)))
        assertFalse(BrainSelfTestPolicy.validate("summarize", call("summarize_text", "text", "$SENTENCE today")))
    }

    @Test fun `open app requires exact WhatsApp argument`() {
        assertTrue(BrainSelfTestPolicy.validate("open-app", call("open_app", "app_name", "WhatsApp")))
        assertFalse(BrainSelfTestPolicy.validate("open-app", call("open_app", "app_name", "WhatsApp Business")))
    }

    @Test fun `missing recipient requires clarification without invented address`() {
        assertTrue(BrainSelfTestPolicy.validate(
            "missing-recipient", call("speak_response", "text", "Who is the recipient?")
        ))
        assertFalse(BrainSelfTestPolicy.validate(
            "missing-recipient", call("draft_email", "to", "madeup@example.com")
        ))
        assertFalse(BrainSelfTestPolicy.validate(
            "missing-recipient", call("speak_response", "text", "Recipient is madeup@example.com")
        ))
    }

    private fun call(tool: String, key: String, value: String) = ToolCall(
        tool,
        buildJsonObject { put(key, value) }
    )

    companion object { private const val SENTENCE = "violet cranes cross the quiet lake at dawn" }
}
