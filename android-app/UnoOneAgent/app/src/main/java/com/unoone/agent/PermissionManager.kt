package com.unoone.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService

object PermissionManager {

    val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.CAMERA
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
        val manageStorageEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else true

        return accessibilityEnabled && overlayEnabled && manageStorageEnabled
    }

    fun getNextSystemPermissionIntent(context: Context): Intent? {
        if (!Settings.canDrawOverlays(context)) {
            return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
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
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
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
}