package com.unoone.agent.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallValidatorTest {
    private fun call(tool: String, args: String = "{}") = ToolCall(tool, Json.parseToJsonElement(args).jsonObject)
    @Test fun rejectsUnknownMissingAndExtraArguments() {
        assertNotNull(ToolCallValidator.rejection(call("invented")))
        assertNotNull(ToolCallValidator.rejection(call("create_note", "{\"content\":\"x\"}")))
        assertNotNull(ToolCallValidator.rejection(call("read_screen", "{\"x\":1}")))
    }
    @Test fun typesAreStrictIncludingNullAndListElements() {
        for (arg in listOf("null", "5", "true", "[]", "{}"))
            assertNotNull(ToolCallValidator.rejection(call("speak_response", "{\"text\":$arg}")))
        for (arg in listOf("\"5\"", "1.5", "true", "null", "2147483648"))
            assertNotNull(ToolCallValidator.rejection(call("voice_recording", "{\"duration_seconds\":$arg}")))
        assertNotNull(ToolCallValidator.rejection(call("create_skill", "{\"name\":\"x\",\"steps\":[\"ok\",1]}")))
        assertNull(ToolCallValidator.rejection(call("voice_recording", "{\"duration_seconds\":5}")))
    }
    @Test fun operationEnumsAndCoordinatesFailClosed() {
        for (action in listOf("click", "fill", "type", "long_press", "swipe", "find_and_click", "delete", "invented"))
            assertNotNull(ToolCallValidator.rejection(call("system_control", "{\"action\":\"$action\"}")))
        assertNotNull(ToolCallValidator.rejection(call("system_control", "{\"action\":\"go_home\",\"y\":5}")))
        assertNotNull(ToolCallValidator.rejection(call("prepare_document_fill", "{\"format\":\"exe\"}")))
        for (action in listOf("go_home", "go_back", "scroll_down", "scroll_up", "open_recents", "open_notifications"))
            assertNull(ToolCallValidator.rejection(call("system_control", "{\"action\":\"$action\"}")))
        assertNull(ToolCallValidator.rejection(call("read_screen")))
    }
    @Test fun legacyAdapterIsExplicitAndDoesNotLoosenSchema() {
        val legacy = call("create_skill", "{\"name\":\"x\",\"steps\":\"home|back\"}")
        assertNotNull(ToolCallValidator.rejection(legacy))
        val normalized = ToolCallValidator.adaptLegacySkill(legacy)
        assertNull(ToolCallValidator.rejection(normalized))
        assertEquals(2, normalized.args.getValue("steps").jsonArray.size)
        try {
            ToolCallValidator.adaptLegacySkill(call("create_skill", "{\"name\":\"x\",\"steps\":\"home||back\"}"))
            fail("Empty legacy steps must not disappear")
        } catch (_: IllegalArgumentException) { }
    }
}
