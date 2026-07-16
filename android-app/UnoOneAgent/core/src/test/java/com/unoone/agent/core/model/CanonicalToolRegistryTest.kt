package com.unoone.agent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Internal consistency of [CanonicalToolRegistry] — the single list of tool names the brain may
 * propose. The cross-check that these names agree with `UnoOneToolSet`, `ActionExecutor`, and
 * `SafetyGuard` lives in the `:localbrain` and `:app` test modules (they can see those classes);
 * this test only asserts the registry itself is well-formed so those cross-checks are meaningful.
 */
class CanonicalToolRegistryTest {

    @Test
    fun hasExactly29Tools() {
        assertEquals(29, CanonicalToolRegistry.tools.size)
        assertEquals(29, CanonicalToolRegistry.names.size)
    }

    @Test
    fun toolNamesAreUnique() {
        val names = CanonicalToolRegistry.tools.map { it.name }
        assertEquals("tool names must be unique (no duplicates)", names.size, names.toSet().size)
    }

    @Test
    fun isKnownAcceptsCanonicalNamesAndRejectsOthers() {
        assertTrue(CanonicalToolRegistry.isKnown("create_note"))
        assertTrue(CanonicalToolRegistry.isKnown("deactivate_blind_aid"))
        assertTrue(CanonicalToolRegistry.isKnown("prepare_document_fill"))
        // Names the safety system BLOCKs must never be canonical executable tools.
        assertFalse(CanonicalToolRegistry.isKnown("make_payment"))
        assertFalse(CanonicalToolRegistry.isKnown("send_message"))
        assertFalse(CanonicalToolRegistry.isKnown("install_app"))
        assertFalse(CanonicalToolRegistry.isKnown("access_passwords"))
        assertFalse(CanonicalToolRegistry.isKnown("silent_control"))
        assertFalse(CanonicalToolRegistry.isKnown(""))
        assertFalse(CanonicalToolRegistry.isKnown("not_a_tool"))
    }

    @Test
    fun schemaForReturnsNullForUnknown() {
        assertNull(CanonicalToolRegistry.schemaFor("not_a_tool"))
        assertNull(CanonicalToolRegistry.schemaFor(""))
    }

    @Test
    fun secureBrowserTaskRequiresOriginAndTask() {
        assertTrue(CanonicalToolRegistry.isKnown("secure_browser_task"))
        val s = CanonicalToolRegistry.schemaFor("secure_browser_task")!!
        assertEquals(ToolParamType.STRING, s.param("origin")!!.type)
        assertTrue(s.param("origin")!!.required)
        assertEquals(ToolParamType.STRING, s.param("task")!!.type)
        assertTrue(s.param("task")!!.required)
        assertEquals(2, s.requiredParams.size)
    }

    @Test
    fun createNoteSchemaIsRequiredTitleAndContentOptionalTags() {
        val s = CanonicalToolRegistry.schemaFor("create_note")!!
        assertNotNull(s.param("title"))
        assertEquals(ToolParamType.STRING, s.param("title")!!.type)
        assertTrue(s.param("title")!!.required)
        assertTrue(s.param("content")!!.required)
        val tags = s.param("tags")!!
        assertFalse(tags.required)
    }

    @Test
    fun systemControlHasOneRequiredActionTwoOptional() {
        val s = CanonicalToolRegistry.schemaFor("system_control")!!
        assertEquals(listOf("action"), s.requiredParams.map { it.name })
        assertFalse(s.param("target")!!.required)
        assertFalse(s.param("value")!!.required)
    }

    @Test
    fun createSkillStepsIsARequiredStringList() {
        val s = CanonicalToolRegistry.schemaFor("create_skill")!!
        assertEquals(ToolParamType.STRING_LIST, s.param("steps")!!.type)
        assertTrue(s.param("steps")!!.required)
        assertTrue(s.param("name")!!.required)
    }

    @Test
    fun noArgToolsHaveNoRequiredParams() {
        for (name in listOf("open_chrome", "open_camera", "read_screen", "ocr_screen",
            "check_calendar", "open_calendar", "delete_all_notes", "export_data", "detect_objects",
            "deactivate_blind_aid")) {
            val s = CanonicalToolRegistry.schemaFor(name)!!
            assertTrue("$name should have no required params", s.requiredParams.isEmpty())
            assertTrue("$name should have no params at all", s.params.isEmpty())
        }
    }

    @Test
    fun openDialerNumberIsOptional() {
        val s = CanonicalToolRegistry.schemaFor("open_dialer")!!
        assertEquals(1, s.params.size)
        assertFalse(s.param("number")!!.required)
    }

    @Test
    fun sensitiveToolsAllHaveRequiredArgsSoEmptyCallsAreRejected() {
        // A destructive tool with a required arg can never pass validation with an empty arg map,
        // so the model cannot drive a destructive tool with fabricated/empty parameters.
        for (name in listOf("delete_notes", "send_whatsapp", "draft_email", "open_url")) {
            val s = CanonicalToolRegistry.schemaFor(name)!!
            assertTrue("$name must declare at least one required argument", s.requiredParams.isNotEmpty())
        }
    }
}
