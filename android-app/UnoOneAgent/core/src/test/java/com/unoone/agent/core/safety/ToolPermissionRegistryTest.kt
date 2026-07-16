package com.unoone.agent.core.safety

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the full tool→requirement mapping in [ToolPermissionRegistry], including the corrected
 * gates: read_screen / system_control need Accessibility (NOT the old SYSTEM_ALERT_WINDOW), and
 * ocr_screen needs MediaProjection (NOT overlay). Pure data — no Android context needed.
 */
class ToolPermissionRegistryTest {

    @Test
    fun readScreenAndSystemControlRequireAccessibilityNotOverlay() {
        for (tool in listOf("read_screen", "system_control")) {
            val reqs = ToolPermissionRegistry.requirementsFor(tool)
            assertTrue("$tool must require Accessibility", reqs.any { it is PermissionRequirement.Accessibility })
            assertFalse("$tool must NOT require Overlay", reqs.any { it is PermissionRequirement.Overlay })
        }
    }

    @Test
    fun ocrScreenRequiresMediaProjectionNotOverlay() {
        val reqs = ToolPermissionRegistry.requirementsFor("ocr_screen")
        assertTrue("ocr_screen must require MediaProjection", reqs.any { it is PermissionRequirement.MediaProjection })
        assertFalse("ocr_screen must NOT require Overlay", reqs.any { it is PermissionRequirement.Overlay })
        assertFalse("ocr_screen must NOT require CAMERA", reqs.any { it is PermissionRequirement.RuntimePerm && (it as PermissionRequirement.RuntimePerm).permission == Manifest.permission.CAMERA })
    }

    @Test
    fun cameraAndCalendarMappingsAreCorrect() {
        assertEquals(listOf(Manifest.permission.CAMERA), ToolPermissionRegistry.runtimePermissionsFor("open_camera"))
        assertEquals(listOf(Manifest.permission.READ_CALENDAR), ToolPermissionRegistry.runtimePermissionsFor("check_calendar"))
        assertTrue(ToolPermissionRegistry.runtimePermissionsFor("open_calendar_insert").isEmpty())
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), ToolPermissionRegistry.runtimePermissionsFor("voice_recording"))
    }

    @Test
    fun detectObjectsNeedsCameraOnly() {
        // BlindAidManager is a pure CameraX + ML Kit ODT + haptic/tone/TTS path — it uses no
        // Accessibility service, so detect_objects must require ONLY the CAMERA runtime permission.
        // The prior vestigial Accessibility gate blocked Blind Aid with a misleading "needs system
        // access" prompt even after the camera was granted.
        val reqs = ToolPermissionRegistry.requirementsFor("detect_objects")
        assertTrue(reqs.any { it is PermissionRequirement.RuntimePerm && it.permission == Manifest.permission.CAMERA })
        assertTrue(
            "detect_objects must NOT require Accessibility (got: $reqs)",
            reqs.none { it is PermissionRequirement.Accessibility }
        )
    }

    @Test
    fun intentOnlyToolsNeedNoRuntimePermissions() {
        // These launch other apps via intent — no dangerous runtime permission required.
        for (tool in listOf("open_dialer", "share_text", "draft_email", "send_whatsapp", "open_url", "open_app", "open_chrome", "open_calendar_insert")) {
            assertTrue(
                "$tool should not require runtime permissions (got ${ToolPermissionRegistry.runtimePermissionsFor(tool)})",
                ToolPermissionRegistry.runtimePermissionsFor(tool).isEmpty()
            )
        }
    }

    @Test
    fun localDataToolsNeedNoAccess() {
        for (tool in listOf("create_note", "search_notes", "summarize_text", "delete_notes", "delete_all_notes", "export_data", "speak_response", "create_skill", "deactivate_blind_aid", "prepare_document_fill")) {
            assertEquals(
                "$tool should require only None",
                listOf(PermissionRequirement.None),
                ToolPermissionRegistry.requirementsFor(tool)
            )
        }
    }

    @Test
    fun unknownToolDefaultsToNone() {
        assertEquals(listOf(PermissionRequirement.None), ToolPermissionRegistry.requirementsFor("does_not_exist"))
    }

    @Test
    fun webSearchNeedsNoRuntimePermission() {
        // INTERNET is a normal (manifest) permission, not a runtime one. The offline-first guard
        // (ConnectivityManager check) lives in ActionExecutor, not in the permission table.
        assertEquals(listOf(PermissionRequirement.None), ToolPermissionRegistry.requirementsFor("web_search"))
        assertTrue(ToolPermissionRegistry.runtimePermissionsFor("web_search").isEmpty())
    }
}
