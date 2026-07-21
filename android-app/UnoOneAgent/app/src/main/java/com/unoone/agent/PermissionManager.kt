package com.unoone.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.safety.PermissionRequirement
import com.unoone.agent.phonecontrol.ScreenshotCapture
import com.unoone.agent.screenshot.ScreenshotPermissionActivity

object PermissionManager {

    /**
     * Only permissions needed for the explicitly enabled background voice service are requested
     * during setup. Camera and Calendar are requested by the tool safety pipeline at first use;
     * Contacts and Calendar-write are not declared because this build does not query contacts or
     * write directly to the provider.
     */
    val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun hasAllRuntimePermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun getPermanentlyDeniedPermissions(activity: android.app.Activity): List<String> {
        return REQUIRED_PERMISSIONS.filter { perm ->
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED &&
            !activity.shouldShowRequestPermissionRationale(perm)
        }
    }

    fun hasSystemPermissions(context: Context): Boolean {
        val accessibilityEnabled = UnoOneAccessibilityService.isEnabled()
        val overlayEnabled = Settings.canDrawOverlays(context)
        return accessibilityEnabled && overlayEnabled
    }

    fun getNextSystemPermissionIntent(context: Context): Intent? {
        if (!Settings.canDrawOverlays(context)) {
            return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        }
        if (!UnoOneAccessibilityService.isEnabled()) {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        return null
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }
    }

    /**
     * Returns an intent for the device manufacturer's autostart/battery settings,
     * or null if the device doesn't have a known manufacturer-specific settings screen.
     * Works across Xiaomi (MIUI), Samsung, Huawei, Oppo, Vivo, and Asus.
     */
    fun getAutostartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                tryIntent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
            }
            manufacturer.contains("samsung") -> {
                // Samsung doesn't have a specific autostart screen; battery optimization covers it
                null
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                tryIntent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                )
            }
            manufacturer.contains("oppo") -> {
                tryIntent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                ) ?: tryIntent(
                    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                )
            }
            manufacturer.contains("vivo") -> {
                tryIntent(
                    ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                )
            }
            manufacturer.contains("asus") -> {
                tryIntent(
                    ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverActivity")
                )
            }
            manufacturer.contains("oneplus") -> {
                tryIntent(
                    ComponentName("com.oneplus.security", "com.oneplus.security.network.oplaunch.AutoLaunchActivity")
                )
            }
            else -> null
        }
    }

    /**
     * Try to create an intent for a specific component. Returns null if the
     * component doesn't exist on this device (non-MIUI phones, etc.).
     */
    private fun tryIntent(component: ComponentName): Intent? {
        return Intent().setComponent(component)
    }

    /**
     * Whether a given [PermissionRequirement] is currently satisfied on this device. Single source
     * of truth for the access check (SafetyPipeline delegates here).
     */
    fun isRequirementSatisfied(context: Context, requirement: PermissionRequirement): Boolean = when (requirement) {
        is PermissionRequirement.RuntimePerm ->
            ContextCompat.checkSelfPermission(context, requirement.permission) == PackageManager.PERMISSION_GRANTED
        PermissionRequirement.Overlay -> Settings.canDrawOverlays(context)
        PermissionRequirement.Accessibility -> UnoOneAccessibilityService.isEnabled()
        PermissionRequirement.MediaProjection -> ScreenshotCapture.hasPermission()
        PermissionRequirement.None -> true
    }

    /**
     * The settings/activity intent that lets the user grant a [PermissionRequirement], or null for
     * runtime perms (those are requested via `requestPermissions`, not an intent) and for None.
     */
    fun getRequirementIntent(context: Context, requirement: PermissionRequirement): Intent? = when (requirement) {
        is PermissionRequirement.RuntimePerm -> null
        PermissionRequirement.Overlay ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        PermissionRequirement.Accessibility ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        PermissionRequirement.MediaProjection ->
            Intent(context, ScreenshotPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermissionRequirement.None -> null
    }
}
