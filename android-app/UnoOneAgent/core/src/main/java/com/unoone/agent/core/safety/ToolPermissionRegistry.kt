package com.unoone.agent.core.safety

import android.Manifest

/**
 * Single source of truth for which [PermissionRequirement]s each tool needs.
 *
 * Consumed by both [com.unoone.agent.safety.SafetyPipeline] (for the runtime check + system
 * requirement surfacing) and [com.unoone.agent.execution.ActionExecutor] (which used to duplicate
 * an incorrect copy of this table). Corrects the prior mapping where `read_screen`,
 * `ocr_screen`, and `system_control` were all wrongly gated behind `SYSTEM_ALERT_WINDOW`:
 * screen reading / UI control need the **Accessibility** service, and screenshot OCR needs a
 * **MediaProjection** token — not the overlay permission.
 *
 * Risk-level gating (block/confirm/strong-confirm) is separate and lives in SafetyGuard; this
 * table only describes *access* requirements.
 */
object ToolPermissionRegistry {

    private val table: Map<String, List<PermissionRequirement>> = mapOf(
        // Notes / memory / data — local-only, no system access.
        "create_note" to listOf(PermissionRequirement.None),
        "search_notes" to listOf(PermissionRequirement.None),
        "summarize_text" to listOf(PermissionRequirement.None),
        "speak_response" to listOf(PermissionRequirement.None),
        "delete_notes" to listOf(PermissionRequirement.None),
        "delete_all_notes" to listOf(PermissionRequirement.None),
        "export_data" to listOf(PermissionRequirement.None),

        // Skills — local-only.
        "create_skill" to listOf(PermissionRequirement.None),

        // Communication apps — launched via intent; no runtime permission needed.
        "draft_email" to listOf(PermissionRequirement.None),
        "send_whatsapp" to listOf(PermissionRequirement.None),
        "open_dialer" to listOf(PermissionRequirement.None), // dialer intent is safe; no permission
        "share_text" to listOf(PermissionRequirement.None),

        // Browser / camera.
        "open_chrome" to listOf(PermissionRequirement.None),
        "open_url" to listOf(PermissionRequirement.None),
        "open_app" to listOf(PermissionRequirement.None),
        "open_camera" to listOf(PermissionRequirement.RuntimePerm(Manifest.permission.CAMERA)),

        // Calendar.
        "open_calendar" to listOf(PermissionRequirement.None), // launcher intent; no permission
        "check_calendar" to listOf(PermissionRequirement.RuntimePerm(Manifest.permission.READ_CALENDAR)),
        // ACTION_INSERT only opens the calendar's own review UI; UnoOne never writes the provider.
        "open_calendar_insert" to listOf(PermissionRequirement.None),

        // Screen / UI control — Accessibility (NOT overlay).
        "system_control" to listOf(PermissionRequirement.Accessibility),
        "read_screen" to listOf(PermissionRequirement.Accessibility),

        // Screenshot OCR — MediaProjection (NOT overlay). Camera not required for screenshots.
        "ocr_screen" to listOf(PermissionRequirement.MediaProjection),

        // Scene description captures a screenshot (MediaProjection) for OCR/vision; same access
        // surface as ocr_screen. STRONG_CONFIRM risk (in SafetyGuard) gates the sensitivity.
        "describe_scene" to listOf(PermissionRequirement.MediaProjection),

        // Voice capture.
        "voice_recording" to listOf(PermissionRequirement.RuntimePerm(Manifest.permission.RECORD_AUDIO)),

        // Web search — online lookup via RAGManager. INTERNET is a normal (non-runtime) permission
        // declared in the manifest, so no runtime request is needed; the offline-first guard
        // (ConnectivityManager check) lives in ActionExecutor.
        "web_search" to listOf(PermissionRequirement.None),

        // Secure Browser task — opens the hardened WebView session. No runtime permission is needed
        // at the tool level: navigation is origin-gated by ApprovedOriginPolicy and the in-browser
        // action policy handles its own confirm/takeover. Risk tier CONFIRM lives in SafetyGuard.
        "secure_browser_task" to listOf(PermissionRequirement.None),
        "prepare_document_fill" to listOf(PermissionRequirement.None),

        // Blind aid — CameraX preview + on-device ML Kit object detection + haptic/tone/TTS guidance.
        // Pure camera path (BlindAidManager uses no Accessibility service), so it needs ONLY the
        // CAMERA runtime permission — not the Accessibility gate it previously carried. That vestigial
        // Accessibility requirement is what blocked Blind Aid with a misleading "needs system access"
        // prompt even after the camera was granted. STRONG_CONFIRM risk (in SafetyGuard) still gates it.
        "detect_objects" to listOf(
            PermissionRequirement.RuntimePerm(Manifest.permission.CAMERA)
        ),

        "deactivate_blind_aid" to listOf(PermissionRequirement.None),

        // Compound commands are expanded into real steps before execution; their requirements are
        // checked per-step, so the compound name itself needs nothing.
        "compound" to listOf(PermissionRequirement.None)
    )

    /** Requirements for a tool; empty list ⇒ unknown tool (treat as None at the caller). */
    fun requirementsFor(tool: String): List<PermissionRequirement> =
        table[tool] ?: listOf(PermissionRequirement.None)

    /** Just the runtime (dangerous) permission strings a tool needs. */
    fun runtimePermissionsFor(tool: String): List<String> =
        requirementsFor(tool).filterIsInstance<PermissionRequirement.RuntimePerm>().map { it.permission }
}
