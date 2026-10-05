package com.unoone.agent.accessibilitycontrol

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.InputSanitizer
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.delay

class AccessibilityControl {

    fun isServiceEnabled(): Boolean {
        return UnoOneAccessibilityService.isEnabled()
    }

    fun clickText(text: String): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

    fun typeText(text: String): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

    fun fillField(hint: String, text: String): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

    fun clickCoords(x: Float, y: Float): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

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

    fun swipe(direction: String): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

    fun longPress(x: Float, y: Float): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

    /**
     * Finds a node by text and performs a long-press gesture at its center coordinates.
     */
    fun longPressNodeWithText(text: String): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

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

    suspend fun findAndClick(text: String, maxScrolls: Int = 5): Result<Unit> { return Result.Error("Manual takeover required: use guarded device workflow") }

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
