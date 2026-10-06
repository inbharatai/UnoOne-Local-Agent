package com.unoone.agent.task

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskToolAuthorizationTest {
    private fun call(tool: String = "create_note", body: String = "private value") =
        ToolCall(tool, buildJsonObject { put("body", body); put("title", "A") })

    @Test fun changedArgumentsDoNotInheritNativeGrant() {
        assertNotEquals(TaskToolAuthorization.handle(call()), TaskToolAuthorization.handle(call(body = "different")))
        assertNotEquals(TaskToolAuthorization.handle(call()), TaskToolAuthorization.handle(call(tool = "delete_notes")))
    }
    @Test fun keyOrderDoesNotInvalidateExactNativeGrant() {
        val reordered = ToolCall("create_note", buildJsonObject { put("title", "A"); put("body", "private value") })
        assertEquals(TaskToolAuthorization.handle(call()), TaskToolAuthorization.handle(reordered))
    }
    @Test fun scopeHandleContainsNoTypedData() {
        val handle = TaskToolAuthorization.handle(call())
        assertFalse(handle.contains("private value"))
        assertEquals(76, handle.length)
    }
}
