package com.unoone.agent.core.safety

/**
 * The kinds of permission/access a tool may need. Replaces the old ad-hoc mix of runtime
 * permission strings and `SYSTEM_ALERT_WINDOW` overloading (which incorrectly gated accessibility
 * and screen-reading tools behind the overlay permission).
 *
 * - [RuntimePerm]: a standard dangerous permission requested via `requestPermissions` (e.g. CAMERA).
 * - [Overlay]: `SYSTEM_ALERT_WINDOW` / "draw over other apps" — for floating UI.
 * - [Accessibility]: the UnoOne AccessibilityService must be enabled.
 * - [MediaProjection]: a MediaProjection token must be granted (for screenshot OCR).
 * - [None]: no access required.
 */
sealed class PermissionRequirement {
    data class RuntimePerm(val permission: String) : PermissionRequirement()
    data object Overlay : PermissionRequirement()
    data object Accessibility : PermissionRequirement()
    data object MediaProjection : PermissionRequirement()
    data object None : PermissionRequirement()
}