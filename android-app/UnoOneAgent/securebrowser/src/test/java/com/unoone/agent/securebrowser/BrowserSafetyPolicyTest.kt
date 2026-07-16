package com.unoone.agent.securebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSafetyPolicyTest {

    @Test
    fun `ordinary form input is allowed`() {
        val decision = BrowserSafetyPolicy.evaluate("input_text", "First name")
        assertEquals(
            BrowserActionDecision.Allow(BrowserActionClass.ORDINARY_INPUT),
            decision
        )
    }

    @Test
    fun `file transfer and final submit require confirmation`() {
        assertTrue(BrowserSafetyPolicy.evaluate("upload_file") is BrowserActionDecision.Confirm)
        assertTrue(BrowserSafetyPolicy.evaluate("submit_form") is BrowserActionDecision.Confirm)
    }

    @Test
    fun `credentials otp captcha and legal acceptance require user takeover`() {
        assertTrue(BrowserSafetyPolicy.evaluate("enter_password") is BrowserActionDecision.UserTakeover)
        assertTrue(BrowserSafetyPolicy.evaluate("input_text", "OTP verification code") is BrowserActionDecision.UserTakeover)
        assertTrue(BrowserSafetyPolicy.evaluate("click_element_by_index", "I am not a robot CAPTCHA") is BrowserActionDecision.UserTakeover)
        assertTrue(BrowserSafetyPolicy.evaluate("accept_terms") is BrowserActionDecision.UserTakeover)
    }

    @Test
    fun `payments are blocked`() {
        val decision = BrowserSafetyPolicy.evaluate("click_element_by_index", "Pay now using card")
        assertTrue(decision is BrowserActionDecision.Block)
        assertEquals(BrowserActionClass.PAYMENT, (decision as BrowserActionDecision.Block).actionClass)
    }

    @Test
    fun `unknown browser action defaults to confirmation`() {
        assertTrue(BrowserSafetyPolicy.evaluate("unknown_action") is BrowserActionDecision.Confirm)
    }

    @Test
    fun `prototype off allows every class while retaining classification`() {
        val payment = BrowserSafetyPolicy.evaluate(
            "click_element_by_index",
            "Pay now using card",
            BrowserSafetyMode.PROTOTYPE_OFF
        )
        val credential = BrowserSafetyPolicy.evaluate(
            "enter_password",
            mode = BrowserSafetyMode.PROTOTYPE_OFF
        )

        assertEquals(BrowserActionDecision.Allow(BrowserActionClass.PAYMENT), payment)
        assertEquals(BrowserActionDecision.Allow(BrowserActionClass.CREDENTIAL), credential)
    }
}
