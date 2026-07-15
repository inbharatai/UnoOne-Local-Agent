package com.unoone.agent.phonecontrol

import android.Manifest
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.safety.PermissionRequirement
import com.unoone.agent.core.safety.ToolPermissionRegistry
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.safetyguard.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real on-device, HEADLESS proof of the camera-access wiring (the "option to access camera" the
 * user asked for). UnoOne reaches the camera through two canonical tools:
 *
 * - `open_camera` — launches the system camera (MediaStore.ACTION_IMAGE_CAPTURE), risk CONFIRM,
 *   gated on the CAMERA runtime permission.
 * - `detect_objects` (Blind Aid) — CameraX preview + on-device object detection with haptic +
 *   spoken guidance, risk STRONG_CONFIRM, gated on the CAMERA runtime permission only (BlindAidManager
 *   is a pure camera path and uses no Accessibility service). `deactivate_blind_aid` stops it
 *   (risk DIRECT, no permission).
 *
 * This test proves the capability is REGISTERED, PERMISSION-GATED, and SAFETY-CLASSIFIED on the
 * device — the accurate, no-dummy headless proof. It does NOT capture a real photo or run live
 * object detection (those need camera hardware + a preview surface and are manual device gates in
 * DEVICE_VERIFICATION.md §7). No fake capture is performed.
 *
 * Run: am instrument -e class com.unoone.agent.phonecontrol.CameraAccessHeadlessTest \
 *   com.unoone.agent.test/androidx.test.runner.AndroidJUnitRunner
 */
class CameraAccessHeadlessTest {

    private val guard = SafetyGuard()

    @Test
    fun cameraToolsAreCanonical() {
        assertTrue("open_camera must be a canonical tool", CanonicalToolRegistry.isKnown("open_camera"))
        assertTrue("detect_objects (Blind Aid) must be a canonical tool", CanonicalToolRegistry.isKnown("detect_objects"))
        assertTrue("deactivate_blind_aid must be a canonical tool", CanonicalToolRegistry.isKnown("deactivate_blind_aid"))
    }

    @Test
    fun openCameraIsGatedOnCameraPermissionAndConfirmRisk() {
        assertEquals(
            "open_camera must require the CAMERA runtime permission",
            listOf(Manifest.permission.CAMERA),
            ToolPermissionRegistry.runtimePermissionsFor("open_camera")
        )
        assertEquals(
            "open_camera must be CONFIRM risk (launching the camera is user-visible)",
            RiskLevel.CONFIRM,
            guard.classify("open_camera")
        )
    }

    @Test
    fun blindAidIsGatedOnCameraOnlyAndStrongConfirm() {
        val reqs = ToolPermissionRegistry.requirementsFor("detect_objects")
        assertTrue(
            "detect_objects must require the CAMERA runtime permission (got: $reqs)",
            reqs.any { it is PermissionRequirement.RuntimePerm && it.permission == Manifest.permission.CAMERA }
        )
        assertTrue(
            "detect_objects must NOT require the Accessibility service — BlindAidManager is a pure camera path (got: $reqs)",
            reqs.none { it is PermissionRequirement.Accessibility }
        )
        assertEquals(
            "detect_objects (Blind Aid) must be STRONG_CONFIRM risk",
            RiskLevel.STRONG_CONFIRM,
            guard.classify("detect_objects")
        )
    }

    @Test
    fun deactivateBlindAidIsSafeAndPermissionFree() {
        val reqs = ToolPermissionRegistry.requirementsFor("deactivate_blind_aid")
        assertTrue(
            "deactivate_blind_aid must require no permission (got: $reqs)",
            reqs.all { it is PermissionRequirement.None }
        )
        assertEquals(
            "deactivate_blind_aid must be DIRECT risk",
            RiskLevel.DIRECT,
            guard.classify("deactivate_blind_aid")
        )
    }

    @Test
    fun ocrPathDoesNotRequireCamera() {
        // OCR runs on a MediaProjection screenshot, NOT the camera. Guards against accidentally
        // coupling the screen-reading path to a camera permission.
        val ocrReqs = ToolPermissionRegistry.requirementsFor("ocr_screen")
        assertTrue(
            "ocr_screen must NOT require the CAMERA permission (got: $ocrReqs)",
            ocrReqs.none { it is PermissionRequirement.RuntimePerm && it.permission == Manifest.permission.CAMERA }
        )
        assertTrue(
            "ocr_screen must require MediaProjection (got: $ocrReqs)",
            ocrReqs.any { it is PermissionRequirement.MediaProjection }
        )
    }
}