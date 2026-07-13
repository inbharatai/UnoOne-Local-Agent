package com.unoone.agent.securebrowser

import kotlinx.serialization.Serializable

@Serializable
enum class PageAgentRequestType {
    MODEL_INVOKE,
    AUTHORIZE_ACTION,
    ACTIVITY_EVENT,
    TASK_RESULT,
    ASK_USER,
    USER_TAKEOVER,
    AUDIT_EVENT
}

@Serializable
data class PageAgentBridgeRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val sessionNonce: String,
    val origin: String,
    val type: PageAgentRequestType,
    val payload: String
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}

@Serializable
data class PageAgentBridgeResponse(
    val protocolVersion: Int = PageAgentBridgeRequest.PROTOCOL_VERSION,
    val requestId: String,
    val success: Boolean,
    val payload: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class PageAgentModelInvocation(
    val systemPrompt: String,
    val userPrompt: String,
    val macroToolSchemaJson: String,
    val maxOutputTokens: Int = 512
)

@Serializable
data class PageAgentModelDecision(
    val evaluationPreviousGoal: String,
    val memory: String,
    val nextGoal: String,
    val actionName: String,
    val actionArgumentsJson: String
)

@Serializable
enum class BrowserActionClass {
    READ_ONLY,
    ORDINARY_INPUT,
    SENSITIVE_INPUT,
    FILE_TRANSFER,
    LOGIN_HANDOFF,
    FINAL_SUBMISSION,
    LEGAL_ACCEPTANCE,
    PAYMENT,
    CREDENTIAL,
    CAPTCHA
}

@Serializable
data class BrowserActionAuthorizationRequest(
    val actionName: String,
    val summary: String,
    val elementIndex: Int? = null,
    val fieldLabel: String? = null,
    val valueCategory: String? = null
)

@Serializable
data class BrowserActionAuthorizationResponse(
    val allowed: Boolean,
    val requiresUserTakeover: Boolean = false,
    val actionClass: BrowserActionClass,
    val message: String
)

@Serializable
data class BrowserAuditEvent(
    val sessionId: String,
    val origin: String,
    val actionName: String,
    val actionClass: BrowserActionClass,
    val summary: String,
    val decision: String,
    val message: String,
    val timestampEpochMs: Long
)
