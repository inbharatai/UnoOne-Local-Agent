package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.Result

/**
 * Abstraction for accessibility service actions (click, type, scroll, etc.).
 * Enables testing without a real accessibility service running.
 */
interface IAccessibilityControl {
    fun clickText(text: String): Result<Unit>
    fun typeText(text: String): Result<Unit>
    fun fillField(label: String, value: String): Result<Unit>
    fun scrollDown(): Result<Unit>
    fun scrollUp(): Result<Unit>
    fun swipe(direction: String): Result<Unit>
    fun longPress(x: Float, y: Float): Result<Unit>
    fun longPressNodeWithText(text: String): Result<Unit>
    fun goBack(): Result<Unit>
    fun goHome(): Result<Unit>
    fun openNotifications(): Result<Unit>
    fun openRecents(): Result<Unit>
    fun findAndClick(text: String): Result<Unit>
    suspend fun captureScreenText(): Result<String>
}