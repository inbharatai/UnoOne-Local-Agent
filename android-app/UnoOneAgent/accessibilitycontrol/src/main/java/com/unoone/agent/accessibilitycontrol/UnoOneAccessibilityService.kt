package com.unoone.agent.accessibilitycontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.unoone.agent.core.util.Logger

class UnoOneAccessibilityService : AccessibilityService() {

    var currentPackage: String? = null
        private set
    var currentActivity: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
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

    fun clickAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        return dispatchGesture(builder.build(), null, null)
    }

    fun clickNodeWithText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        return false
    }

    fun typeTextIntoFocused(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun fillFieldWithText(hint: String, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(hint)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
        }
        return false
    }

    fun captureVisibleText(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo) {
            node.text?.let { texts.add(it.toString()) }
            node.contentDescription?.let { texts.add(it.toString()) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }
        traverse(rootNode)
        return texts.distinct()
    }

    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val bounds = android.graphics.Rect()
        rootNode.getBoundsInScreen(bounds)
        val centerX = bounds.exactCenterX()
        val startY = bounds.exactCenterY() + bounds.height() * 0.25f
        val endY = bounds.exactCenterY() - bounds.height() * 0.25f
        return performSwipe(centerX, startY, centerX, endY, 500L)
    }

    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val bounds = android.graphics.Rect()
        rootNode.getBoundsInScreen(bounds)
        val centerX = bounds.exactCenterX()
        val startY = bounds.exactCenterY() - bounds.height() * 0.25f
        val endY = bounds.exactCenterY() + bounds.height() * 0.25f
        return performSwipe(centerX, startY, centerX, endY, 500L)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    private fun performSwipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long): Boolean {
        return swipe(sx, sy, ex, ey, duration)
    }

    companion object {
        private var instance: UnoOneAccessibilityService? = null
        fun getInstance(): UnoOneAccessibilityService? = instance
        fun isEnabled(): Boolean = instance != null
    }
}