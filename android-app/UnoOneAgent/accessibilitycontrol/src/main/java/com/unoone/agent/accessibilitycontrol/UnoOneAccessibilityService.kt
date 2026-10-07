package com.unoone.agent.accessibilitycontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.unoone.agent.core.util.Logger
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.device.sensitiveObservation

class UnoOneAccessibilityService : AccessibilityService() {

    @Volatile var currentPackage: String? = null
        private set
    @Volatile var currentActivity: String? = null
        private set

    private val sequence = java.util.concurrent.atomic.AtomicLong()
    val eventSequence: Long get() = sequence.get()
    @Volatile var lastEventAtMs: Long = 0
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        sequence.incrementAndGet()
        lastEventAtMs = android.os.SystemClock.elapsedRealtime()
        if (!AgentRuntimeGate.isEnabled()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = event.packageName?.toString()
            currentActivity = event.className?.toString()
        }
    }

    override fun onInterrupt() {
        Logger.w("Accessibility Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("Accessibility Service Connected - UnoOne is now in control")
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun clickAt(x: Float, y: Float): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    fun clickNodeWithText(text: String): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    fun typeTextIntoFocused(text: String): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    fun fillFieldWithText(hint: String, text: String): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    @Suppress("DEPRECATION")
    fun captureVisibleText(): List<String> {
        if (!AgentRuntimeGate.isEnabled()) return emptyList()
        return runCatching {
            val safeNodes = com.unoone.agent.core.device.SensitiveReadRedaction.redactNodes(AndroidDeviceAdapter(this).captureSnapshot().nodes)
            val text = safeNodes.filter { !it.password && !it.semantic.sensitiveObservation() }
                .map { it.text.ifEmpty { it.description } }.filter { it.isNotBlank() }.distinct().joinToString("\n")
            com.unoone.agent.core.device.SensitiveReadRedaction.redactText(text).lines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun scrollDown(): Boolean {
        if (!AgentRuntimeGate.isEnabled()) return false
        val rootNode = rootInActiveWindow ?: return false
        try {
            val bounds = android.graphics.Rect()
            rootNode.getBoundsInScreen(bounds)
            val centerX = bounds.exactCenterX()
            val startY = bounds.exactCenterY() + bounds.height() * 0.25f
            val endY = bounds.exactCenterY() - bounds.height() * 0.25f
            return performSwipe(centerX, startY, centerX, endY, 500L)
        } finally {
            rootNode.recycle()
        }
    }

    fun scrollUp(): Boolean {
        if (!AgentRuntimeGate.isEnabled()) return false
        val rootNode = rootInActiveWindow ?: return false
        try {
            val bounds = android.graphics.Rect()
            rootNode.getBoundsInScreen(bounds)
            val centerX = bounds.exactCenterX()
            val startY = bounds.exactCenterY() - bounds.height() * 0.25f
            val endY = bounds.exactCenterY() + bounds.height() * 0.25f
            return performSwipe(centerX, startY, centerX, endY, 500L)
        } finally {
            rootNode.recycle()
        }
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    fun longPress(x: Float, y: Float): Boolean { return false /* Retired: use AndroidDeviceAdapter with issued snapshot and guard. */ }

    fun goBack(): Boolean = AgentRuntimeGate.isEnabled() && performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = AgentRuntimeGate.isEnabled() && performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = AgentRuntimeGate.isEnabled() && performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = AgentRuntimeGate.isEnabled() && performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = AgentRuntimeGate.isEnabled() && performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    private fun performSwipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean {
        if (!AgentRuntimeGate.isEnabled()) return false
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return AgentRuntimeGate.isEnabled() && dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile
        private var instance: UnoOneAccessibilityService? = null
        fun getInstance(): UnoOneAccessibilityService? =
            instance?.takeIf { AgentRuntimeGate.isEnabled() }
        fun isEnabled(): Boolean = instance != null && AgentRuntimeGate.isEnabled()
    }
}
