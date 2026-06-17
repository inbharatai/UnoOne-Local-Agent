package com.unoone.agent.core.interfaces

import com.unoone.agent.core.model.Result

/**
 * Abstraction for phone control actions (calls, WhatsApp, browser, camera).
 * Enables testing without real device interactions.
 */
interface IPhoneControl {
    fun openChrome(): Result<Unit>
    fun openCamera(): Result<Unit>
    fun draftEmail(to: String, subject: String, body: String): Result<Unit>
    fun sendWhatsAppMessage(number: String, message: String): Result<Unit>
}