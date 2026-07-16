package com.unoone.agent.securebrowser

sealed class BrowserActionDecision {
    data class Allow(val actionClass: BrowserActionClass) : BrowserActionDecision()
    data class Confirm(val actionClass: BrowserActionClass, val message: String) : BrowserActionDecision()
    data class UserTakeover(val actionClass: BrowserActionClass, val message: String) : BrowserActionDecision()
    data class Block(val actionClass: BrowserActionClass, val reason: String) : BrowserActionDecision()
}

/** Explicit browser authorization posture. STANDARD is always the default. */
enum class BrowserSafetyMode { STANDARD, PROTOTYPE_OFF }

/**
 * Deterministic safety boundary around PageAgent actions.
 *
 * PageAgent may navigate and fill ordinary fields, but passwords, OTPs, CAPTCHA, payments, legal
 * acceptance and final submissions never proceed autonomously.
 */
object BrowserSafetyPolicy {

    /**
     * Keeps deterministic action classification in every mode, while allowing an explicitly chosen
     * local prototype session to bypass confirm/takeover/block decisions. Origin isolation and the
     * restricted native bridge are enforced elsewhere and are never disabled by this setting.
     */
    fun evaluate(
        actionName: String,
        summary: String = "",
        mode: BrowserSafetyMode = BrowserSafetyMode.STANDARD
    ): BrowserActionDecision {
        val classified = classify(actionName, summary)
        return if (mode == BrowserSafetyMode.PROTOTYPE_OFF) {
            BrowserActionDecision.Allow(classified.actionClass())
        } else {
            classified
        }
    }

    private fun classify(actionName: String, summary: String): BrowserActionDecision {
        val action = actionName.trim().lowercase()
        val text = summary.lowercase()

        if (containsAny(text, PAYMENT_TERMS) || action in PAYMENT_ACTIONS) {
            return BrowserActionDecision.Block(BrowserActionClass.PAYMENT, "Payments are not automated by UnoOne")
        }
        if (containsAny(text, CREDENTIAL_TERMS) || action in CREDENTIAL_ACTIONS) {
            return BrowserActionDecision.UserTakeover(
                BrowserActionClass.CREDENTIAL,
                "The user must enter credentials directly"
            )
        }
        if (containsAny(text, OTP_TERMS) || action in OTP_ACTIONS) {
            return BrowserActionDecision.UserTakeover(
                BrowserActionClass.CREDENTIAL,
                "OTP entry requires user takeover"
            )
        }
        if (containsAny(text, CAPTCHA_TERMS) || action in CAPTCHA_ACTIONS) {
            return BrowserActionDecision.UserTakeover(
                BrowserActionClass.CAPTCHA,
                "CAPTCHA must be completed by the user"
            )
        }
        if (containsAny(text, LEGAL_TERMS) || action in LEGAL_ACTIONS) {
            return BrowserActionDecision.UserTakeover(
                BrowserActionClass.LEGAL_ACCEPTANCE,
                "Legal declarations and terms must be reviewed and accepted by the user"
            )
        }
        if (containsAny(text, FINAL_SUBMIT_TERMS) || action in FINAL_SUBMIT_ACTIONS) {
            return BrowserActionDecision.Confirm(
                BrowserActionClass.FINAL_SUBMISSION,
                "Review the completed form before final submission"
            )
        }
        if (action in FILE_ACTIONS) {
            return BrowserActionDecision.Confirm(
                BrowserActionClass.FILE_TRANSFER,
                "Confirm the selected file and destination before continuing"
            )
        }
        if (action in ASK_USER_ACTIONS) {
            return BrowserActionDecision.UserTakeover(
                BrowserActionClass.LOGIN_HANDOFF,
                "The browser task requires user input"
            )
        }
        if (action in READ_ACTIONS) return BrowserActionDecision.Allow(BrowserActionClass.READ_ONLY)
        if (action in INPUT_ACTIONS) return BrowserActionDecision.Allow(BrowserActionClass.ORDINARY_INPUT)

        return BrowserActionDecision.Confirm(
            BrowserActionClass.SENSITIVE_INPUT,
            "Unrecognized browser action requires confirmation"
        )
    }

    private fun BrowserActionDecision.actionClass(): BrowserActionClass = when (this) {
        is BrowserActionDecision.Allow -> actionClass
        is BrowserActionDecision.Confirm -> actionClass
        is BrowserActionDecision.UserTakeover -> actionClass
        is BrowserActionDecision.Block -> actionClass
    }

    private fun containsAny(text: String, terms: Set<String>): Boolean = terms.any(text::contains)

    private val READ_ACTIONS = setOf("wait", "scroll", "scroll_horizontally", "done", "extract_text")
    private val INPUT_ACTIONS = setOf(
        "click_element_by_index", "input_text", "select_dropdown_option", "toggle_checkbox",
        "choose_radio", "pick_date", "handle_autocomplete"
    )
    private val FILE_ACTIONS = setOf("upload_file", "download_file")
    private val ASK_USER_ACTIONS = setOf("ask_user", "request_user_takeover", "continue_login")
    private val FINAL_SUBMIT_ACTIONS = setOf("submit_form", "prepare_submission", "confirm_booking")
    private val LEGAL_ACTIONS = setOf("accept_terms", "accept_declaration", "sign_declaration")
    private val PAYMENT_ACTIONS = setOf("make_payment", "pay", "purchase", "checkout")
    private val CREDENTIAL_ACTIONS = setOf("enter_password", "read_password", "use_saved_password")
    private val OTP_ACTIONS = setOf("enter_otp", "submit_otp")
    private val CAPTCHA_ACTIONS = setOf("solve_captcha", "bypass_captcha")

    private val PAYMENT_TERMS = setOf("payment", "card number", "cvv", "upi pin", "bank account", "pay now")
    private val CREDENTIAL_TERMS = setOf("password", "passcode", "pin field", "saved password")
    private val OTP_TERMS = setOf("otp", "one-time password", "verification code")
    private val CAPTCHA_TERMS = setOf("captcha", "i am not a robot", "recaptcha")
    private val LEGAL_TERMS = setOf("accept terms", "legal declaration", "certify that", "declare that")
    private val FINAL_SUBMIT_TERMS = setOf("final submit", "submit application", "confirm booking", "place order")
}
