package com.unoone.agent.securebrowser

import com.unoone.agent.core.runtime.AgentRuntimeGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After

class SecureBrowserNativeHandlerTest {

    private val json = Json { encodeDefaults = true }

    @After
    fun restoreRuntime() {
        AgentRuntimeGate.setEnabled(true)
    }

    @Test
    fun `master disable rejects model and action bridge calls without invoking dependencies`() = runBlocking {
        AgentRuntimeGate.setEnabled(false)
        var modelCalls = 0
        val interaction = FakeInteraction(confirmResult = true)
        val handler = SecureBrowserNativeHandler(
            BrowserModelPort {
                modelCalls++
                Result.failure(Exception("must not run"))
            },
            interaction
        )
        val response = handler.handle(
            request(
                PageAgentRequestType.MODEL_INVOKE,
                json.encodeToString(PageAgentModelInvocation("system", "user", "[]"))
            )
        )

        assertFalse(response.success)
        assertEquals("AGENT_DISABLED", response.errorCode)
        assertEquals(0, modelCalls)
        assertEquals(0, interaction.confirmCalls)
    }

    private fun request(type: PageAgentRequestType, payload: String) = PageAgentBridgeRequest(
        requestId = "req-1",
        sessionId = "session-1",
        sessionNonce = "nonce",
        origin = "https://unigurus.com",
        type = type,
        payload = payload
    )

    @Test
    fun `routes model invocation to local model port`() = runBlocking {
        val handler = SecureBrowserNativeHandler(
            modelPort = BrowserModelPort {
                Result.success(
                    PageAgentModelDecision(
                        evaluationPreviousGoal = "No previous action",
                        memory = "Need first name",
                        nextGoal = "Fill first name",
                        actionName = "input_text",
                        actionArgumentsJson = "{\"index\":1,\"text\":\"Reeturaj\"}"
                    )
                )
            },
            userInteraction = FakeInteraction()
        )
        val invocation = PageAgentModelInvocation("system", "user", "[]")
        val response = handler.handle(request(PageAgentRequestType.MODEL_INVOKE, json.encodeToString(invocation)))

        assertTrue(response.success)
        val decision = json.decodeFromString(PageAgentModelDecision.serializer(), response.payload)
        assertEquals("input_text", decision.actionName)
    }

    @Test
    fun `blocks payment without asking for confirmation`() = runBlocking {
        val interaction = FakeInteraction(confirmResult = true)
        val handler = SecureBrowserNativeHandler(BrowserModelPort { Result.failure(Exception()) }, interaction)
        val input = BrowserActionAuthorizationRequest(
            actionName = "click_element_by_index",
            summary = "Pay now using card"
        )

        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        val auth = json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)

        assertFalse(auth.allowed)
        assertEquals(BrowserActionClass.PAYMENT, auth.actionClass)
        assertEquals(0, interaction.confirmCalls)
    }

    @Test
    fun `final submission uses native confirmation`() = runBlocking {
        val interaction = FakeInteraction(confirmResult = true)
        val handler = SecureBrowserNativeHandler(BrowserModelPort { Result.failure(Exception()) }, interaction)
        val input = BrowserActionAuthorizationRequest(
            actionName = "submit_form",
            summary = "Submit application"
        )

        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        val auth = json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)

        assertTrue(auth.allowed)
        assertEquals(1, interaction.confirmCalls)
    }

    @Test
    fun `otp routes to user takeover and never authorizes the tool directly`() = runBlocking {
        val interaction = FakeInteraction(takeoverResult = true)
        val handler = SecureBrowserNativeHandler(BrowserModelPort { Result.failure(Exception()) }, interaction)
        val input = BrowserActionAuthorizationRequest(
            actionName = "input_text",
            summary = "OTP verification code"
        )

        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        val auth = json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)

        assertFalse(auth.allowed)
        assertTrue(auth.requiresUserTakeover)
        assertEquals(1, interaction.takeoverCalls)
    }

    @Test
    fun `explicit prototype mode allows classified payment without prompt`() = runBlocking {
        val interaction = FakeInteraction(confirmResult = false, takeoverResult = false)
        val handler = SecureBrowserNativeHandler(
            modelPort = BrowserModelPort { Result.failure(Exception()) },
            userInteraction = interaction,
            safetyModeProvider = { BrowserSafetyMode.PROTOTYPE_OFF }
        )
        val input = BrowserActionAuthorizationRequest(
            actionName = "click_element_by_index",
            summary = "Pay now using card"
        )

        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        val auth = json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)

        assertTrue(auth.allowed)
        assertEquals(BrowserActionClass.PAYMENT, auth.actionClass)
        assertEquals(0, interaction.confirmCalls)
        assertEquals(0, interaction.takeoverCalls)
        assertTrue(auth.message.contains("prototype browser safety is off"))
    }

    private class FakeInteraction(
        private val confirmResult: Boolean = false,
        private val takeoverResult: Boolean = false
    ) : BrowserUserInteraction {
        var confirmCalls = 0
        var takeoverCalls = 0

        override suspend fun confirm(message: String): Boolean {
            confirmCalls++
            return confirmResult
        }

        override suspend fun ask(question: String): String = "answer"

        override suspend fun requestTakeover(message: String): Boolean {
            takeoverCalls++
            return takeoverResult
        }
    }
}
