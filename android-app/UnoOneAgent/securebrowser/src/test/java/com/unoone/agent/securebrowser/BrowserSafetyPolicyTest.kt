package com.unoone.agent.securebrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSafetyPolicyTest {

    @Test
    fun `ordinary form input requires native confirmation`() {
        val decision = BrowserSafetyPolicy.evaluate("input_text", "First name")
        // Field changes mutate page state and require explicit native consent.
        assertTrue(decision is BrowserActionDecision.Confirm)
        assertEquals(BrowserActionClass.ORDINARY_INPUT, (decision as BrowserActionDecision.Confirm).actionClass)
        assertTrue(decision.message.contains("First name"))
    }

    @Test
    fun `file transfer requires confirmation and final submit requires takeover`() {
        assertTrue(BrowserSafetyPolicy.evaluate("upload_file") is BrowserActionDecision.Confirm)
        assertTrue(BrowserSafetyPolicy.evaluate("submit_form") is BrowserActionDecision.UserTakeover)
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
    fun `unknown browser action fails closed`() {
        assertTrue(BrowserSafetyPolicy.evaluate("unknown_action") is BrowserActionDecision.Block)
    }

    @Test
    fun `prototype mode never bypasses sensitive action policy`() {
        val payment = BrowserSafetyPolicy.evaluate(
            "click_element_by_index",
            "Pay now using card",
            BrowserSafetyMode.PROTOTYPE_OFF
        )
        val credential = BrowserSafetyPolicy.evaluate(
            "enter_password",
            mode = BrowserSafetyMode.PROTOTYPE_OFF
        )

        // Prototype settings cannot confer payment or credential authority.
        assertTrue(payment is BrowserActionDecision.Block)
        assertEquals(BrowserActionClass.PAYMENT, (payment as BrowserActionDecision.Block).actionClass)
        assertTrue(credential is BrowserActionDecision.UserTakeover)
        assertEquals(BrowserActionClass.CREDENTIAL, (credential as BrowserActionDecision.UserTakeover).actionClass)
    }
    @Test
    fun `prototype retains every strict decision and reads remain allowed`() {
        val cases = listOf(
            "input_text" to "First name", "select_dropdown_option" to "Country",
            "toggle_checkbox" to "Newsletter", "choose_radio" to "Preference",
            "pick_date" to "Arrival", "handle_autocomplete" to "City",
            "submit_form" to "Submit application", "upload_file" to "Resume",
            "input_text" to "OTP verification code", "solve_captcha" to "",
            "accept_terms" to "", "unknown_action" to "",
            "click_element_by_index" to "", "click_element_by_index" to "Interactive element index 4",
            "click_element_by_index" to "Next"
        )
        for ((action, summary) in cases) {
            val standard = BrowserSafetyPolicy.evaluate(action, summary)
            assertTrue("$action must not execute autonomously", standard !is BrowserActionDecision.Allow)
            assertEquals(standard, BrowserSafetyPolicy.evaluate(action, summary, BrowserSafetyMode.PROTOTYPE_OFF))
        }
        for (mode in BrowserSafetyMode.values()) {
            assertEquals(BrowserActionDecision.Allow(BrowserActionClass.READ_ONLY), BrowserSafetyPolicy.evaluate("extract_text", mode = mode))
        }
    }
}
