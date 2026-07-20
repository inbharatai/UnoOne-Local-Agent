package com.unoone.agent.accessibilitycontrol

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.delay

class AccessibilityControl {

    fun isServiceEnabled(): Boolean {
        return UnoOneAccessibilityService.isEnabled()
    }

    fun clickText(text: String): Result<Unit> {
        val safeText = InputSanitizer.sanitizeForAccessibility(text)
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")

        return if (service.clickNodeWithText(safeText)) {
            Result.Success(Unit)
        } else {
            Result.Error("Could not find or click text: $safeText")
        }
    }

    fun typeText(text: String): Result<Unit> {
        val safeText = InputSanitizer.sanitizeForAccessibility(text)
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")

        return if (service.typeTextIntoFocused(safeText)) {
            Result.Success(Unit)
        } else {
            Result.Error("Could not type text - no input field focused")
        }
    }

    fun fillField(hint: String, text: String): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")

        return if (service.fillFieldWithText(hint, text)) {
            Result.Success(Unit)
        } else {
            Result.Error("Could not find field with hint: $hint")
        }
    }

    fun clickCoords(x: Float, y: Float): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")

        return if (service.clickAt(x, y)) {
            Result.Success(Unit)
        } else {
            Result.Error("Failed to perform click at ($x, $y)")
        }
    }

    fun captureScreenText(): Result<String> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        val texts = service.captureVisibleText()
        return if (texts.isNotEmpty()) {
            val fullText = texts.joinToString("\n")
            val cleanText = limitInputToSafeThreshold(fullText)
            Result.Success(cleanText)
        } else {
            Result.Error("No text found on screen")
        }
    }

    private fun limitInputToSafeThreshold(input: String, maxChars: Int = 100_000): String {
        if (input.length > maxChars) {
            return "... [Truncated due to length] ... \n" + input.takeLast(maxChars)
        }
        return input
    }

    fun scrollDown(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.scrollDown()) Result.Success(Unit)
        else Result.Error("Failed to scroll down")
    }

    fun scrollUp(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.scrollUp()) Result.Success(Unit)
        else Result.Error("Failed to scroll up")
    }

    fun swipe(direction: String): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        val rootNode = service.rootInActiveWindow
            ?: return Result.Error("No active window")
        try {
            val bounds = android.graphics.Rect()
            rootNode.getBoundsInScreen(bounds)
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val dx = bounds.width() * 0.4f
            val dy = bounds.height() * 0.4f
            val result = when (direction.lowercase()) {
                "left" -> service.swipe(cx + dx, cy, cx - dx, cy)
                "right" -> service.swipe(cx - dx, cy, cx + dx, cy)
                "up" -> service.swipe(cx, cy + dy, cx, cy - dy)
                "down" -> service.swipe(cx, cy - dy, cx, cy + dy)
                else -> return Result.Error("Unknown swipe direction: $direction")
            }
            return if (result) Result.Success(Unit) else Result.Error("Swipe failed")
        } finally {
            rootNode.recycle()
        }
    }

    fun longPress(x: Float, y: Float): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.longPress(x, y)) Result.Success(Unit)
        else Result.Error("Long press failed")
    }

    /**
     * Finds a node by text and performs a long-press gesture at its center coordinates.
     */
    fun longPressNodeWithText(text: String): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        val rootNode = service.rootInActiveWindow
            ?: return Result.Error("No active window")
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            try {
                val targetNode = nodes.firstOrNull()
                    ?: return Result.Error("Could not find node with text: $text")
                val bounds = android.graphics.Rect()
                targetNode.getBoundsInScreen(bounds)
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                return if (service.longPress(cx, cy)) Result.Success(Unit)
                else Result.Error("Long press on '$text' failed")
            } finally {
                nodes.forEach { it.recycle() }
            }
        } finally {
            rootNode.recycle()
        }
    }

    fun goBack(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.goBack()) Result.Success(Unit)
        else Result.Error("Could not go back")
    }

    fun goHome(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.goHome()) Result.Success(Unit)
        else Result.Error("Could not go home")
    }

    fun openNotifications(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.openNotifications()) Result.Success(Unit)
        else Result.Error("Could not open notifications")
    }

    fun openRecents(): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")
        return if (service.openRecents()) Result.Success(Unit)
        else Result.Error("Could not open recents")
    }

    suspend fun findAndClick(text: String, maxScrolls: Int = 5): Result<Unit> {
        val service = UnoOneAccessibilityService.getInstance()
            ?: return Result.Error("Accessibility Service not enabled")

        // Try clicking without scrolling first
        if (service.clickNodeWithText(text)) return Result.Success(Unit)

        // Scroll and retry — using delay instead of Thread.sleep to avoid blocking the main thread
        repeat(maxScrolls) {
            service.scrollDown()
            delay(500)
            if (service.clickNodeWithText(text)) return Result.Success(Unit)
        }
        return Result.Error("Could not find '$text' after scrolling")
    }

    fun getCurrentContext(): String? {
        val service = UnoOneAccessibilityService.getInstance() ?: return null
        val pkg = service.currentPackage ?: return null
        val act = service.currentActivity ?: return pkg
        return "$pkg/$act"
    }

    /** Exact foreground package observed from TYPE_WINDOW_STATE_CHANGED events. */
    fun getCurrentPackage(): String? =
        UnoOneAccessibilityService.getInstance()?.currentPackage
}
