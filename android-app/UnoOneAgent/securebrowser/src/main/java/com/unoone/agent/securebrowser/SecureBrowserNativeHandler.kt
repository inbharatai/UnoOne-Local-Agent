package com.unoone.agent.securebrowser

import com.unoone.agent.core.runtime.AgentRuntimeGate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun interface BrowserModelPort {
    suspend fun plan(invocation: PageAgentModelInvocation): Result<PageAgentModelDecision>
}

interface BrowserUserInteraction {
    suspend fun confirm(message: String): Boolean
    suspend fun ask(question: String): String
    suspend fun requestTakeover(message: String): Boolean
}

fun interface BrowserEventSink {
    suspend fun record(type: PageAgentRequestType, payload: String)
}

/**
 * Converts validated WebView bridge messages into local model calls, native confirmation decisions
 * and audit events. This class contains no WebView code and is straightforward to unit test.
 */
class SecureBrowserNativeHandler(
    private val modelPort: BrowserModelPort,
    private val userInteraction: BrowserUserInteraction,
    private val eventSink: BrowserEventSink = BrowserEventSink { _, _ -> },
    /** Read per action so changing the local prototype setting does not require recreating WebView. */
    private val safetyModeProvider: () -> BrowserSafetyMode = { BrowserSafetyMode.STANDARD }
) : PageAgentRequestHandler {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun handle(request: PageAgentBridgeRequest): PageAgentBridgeResponse {
        if (!AgentRuntimeGate.isEnabled()) {
            return failure(request, "AGENT_DISABLED", "UnoOne is disabled")
        }
        return when (request.type) {
            PageAgentRequestType.MODEL_INVOKE -> handleModel(request)
            PageAgentRequestType.AUTHORIZE_ACTION -> handleAuthorization(request)
            PageAgentRequestType.ASK_USER -> handleAskUser(request)
            PageAgentRequestType.USER_TAKEOVER -> handleTakeover(request)
            PageAgentRequestType.ACTIVITY_EVENT,
            PageAgentRequestType.TASK_RESULT,
            PageAgentRequestType.AUDIT_EVENT -> {
                eventSink.record(request.type, request.payload)
                success(request, "{}")
            }
        }
    }

    private suspend fun handleModel(request: PageAgentBridgeRequest): PageAgentBridgeResponse {
        val invocation = try {
            json.decodeFromString(PageAgentModelInvocation.serializer(), request.payload)
        } catch (e: Exception) {
            return failure(request, "INVALID_MODEL_REQUEST", e.message ?: "Invalid model request")
        }
        return modelPort.plan(invocation).fold(
            onSuccess = {
                if (AgentRuntimeGate.isEnabled()) success(request, json.encodeToString(it))
                else failure(request, "AGENT_DISABLED", "UnoOne is disabled")
            },
            onFailure = { failure(request, "MODEL_ERROR", it.message ?: "Local model failed") }
        )
    }

    private suspend fun handleAuthorization(request: PageAgentBridgeRequest): PageAgentBridgeResponse {
        val input = try {
            json.decodeFromString(BrowserActionAuthorizationRequest.serializer(), request.payload)
        } catch (e: Exception) {
            return failure(request, "INVALID_ACTION_REQUEST", e.message ?: "Invalid action request")
        }

        val safetyMode = safetyModeProvider()
        val decision = BrowserSafetyPolicy.evaluate(
            input.actionName,
            listOfNotNull(input.summary, input.fieldLabel, input.valueCategory).joinToString(" | "),
            safetyMode
        )

        val response = when (decision) {
            is BrowserActionDecision.Allow -> BrowserActionAuthorizationResponse(
                allowed = true,
                actionClass = decision.actionClass,
                message = if (safetyMode == BrowserSafetyMode.PROTOTYPE_OFF) {
                    "Allowed — prototype browser safety is off"
                } else {
                    "Allowed"
                }
            )
            is BrowserActionDecision.Confirm -> {
                val approved = userInteraction.confirm(decision.message)
                BrowserActionAuthorizationResponse(
                    allowed = approved,
                    actionClass = decision.actionClass,
                    message = if (approved) "User confirmed" else "User declined"
                )
            }
            is BrowserActionDecision.UserTakeover -> {
                val completed = userInteraction.requestTakeover(decision.message)
                BrowserActionAuthorizationResponse(
                    allowed = false,
                    requiresUserTakeover = true,
                    actionClass = decision.actionClass,
                    message = if (completed) "User takeover completed; observe the page again" else decision.message
                )
            }
            is BrowserActionDecision.Block -> BrowserActionAuthorizationResponse(
                allowed = false,
                actionClass = decision.actionClass,
                message = decision.reason
            )
        }

        val decisionLabel = when {
            response.allowed -> "allowed"
            response.requiresUserTakeover -> "user_takeover"
            response.actionClass == BrowserActionClass.PAYMENT -> "blocked"
            else -> "declined_or_blocked"
        }
        eventSink.record(
            PageAgentRequestType.AUDIT_EVENT,
            json.encodeToString(
                BrowserAuditEvent(
                    sessionId = request.sessionId,
                    origin = request.origin,
                    actionName = input.actionName,
                    actionClass = response.actionClass,
                    summary = input.summary.take(500),
                    decision = decisionLabel,
                    message = response.message.take(300),
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
        )
        return success(request, json.encodeToString(response))
    }

    private suspend fun handleAskUser(request: PageAgentBridgeRequest): PageAgentBridgeResponse {
        val question = extractStringField(request.payload, "question")
            ?: return failure(request, "INVALID_QUESTION", "Question is missing")
        return success(request, userInteraction.ask(question))
    }

    private suspend fun handleTakeover(request: PageAgentBridgeRequest): PageAgentBridgeResponse {
        val reason = extractStringField(request.payload, "reason") ?: "Browser task requires user takeover"
        val completed = userInteraction.requestTakeover(reason)
        return success(request, if (completed) "completed" else "cancelled")
    }

    private fun extractStringField(payload: String, name: String): String? = runCatching {
        json.parseToJsonElement(payload).jsonObject[name]?.jsonPrimitive?.content
    }.getOrNull()

    private fun success(request: PageAgentBridgeRequest, payload: String): PageAgentBridgeResponse =
        PageAgentBridgeResponse(requestId = request.requestId, success = true, payload = payload)

    private fun failure(
        request: PageAgentBridgeRequest,
        code: String,
        message: String
    ): PageAgentBridgeResponse = PageAgentBridgeResponse(
        requestId = request.requestId,
        success = false,
        errorCode = code,
        errorMessage = message
    )
}
