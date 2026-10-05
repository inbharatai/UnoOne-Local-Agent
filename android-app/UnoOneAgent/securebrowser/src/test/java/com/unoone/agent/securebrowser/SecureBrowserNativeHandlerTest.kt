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
            // Explicit suspend implementation avoids Result boxing in a SAM test double.
            modelPort = object : BrowserModelPort {
                override suspend fun plan(invocation: PageAgentModelInvocation): Result<PageAgentModelDecision> = Result.success(
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
    fun `final submission requires takeover and never returns an execution permit`() = runBlocking {
        val interaction = FakeInteraction(confirmResult = true, takeoverResult = true)
        val handler = SecureBrowserNativeHandler(BrowserModelPort { Result.failure(Exception()) }, interaction)
        val input = BrowserActionAuthorizationRequest(
            actionName = "submit_form",
            summary = "Submit application"
        )

        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        val auth = json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)

        // Completing takeover must not replay the consequential action through the agent.
        assertFalse(auth.allowed)
        assertTrue(auth.requiresUserTakeover)
        assertEquals(BrowserActionClass.FINAL_SUBMISSION, auth.actionClass)
        assertEquals(0, interaction.confirmCalls)
        assertEquals(1, interaction.takeoverCalls)
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
    fun `explicit prototype mode blocks payment without prompt`() = runBlocking {
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

        // Debug/prototype preferences never weaken the native payment prohibition.
        assertFalse(auth.allowed)
        assertEquals(BrowserActionClass.PAYMENT, auth.actionClass)
        assertEquals(0, interaction.confirmCalls)
        assertEquals(0, interaction.takeoverCalls)
        assertFalse(auth.message.contains("prototype browser safety is off"))
    }

    @Test
    fun `ordinary field changes require a fresh confirmation for each request`() = runBlocking {
        for (mode in BrowserSafetyMode.values()) {
            var confirmations = 0
            val interaction = object : BrowserUserInteraction {
                override suspend fun confirm(message: String): Boolean = ++confirmations == 1
                override suspend fun ask(question: String): String = error("Unexpected question")
                override suspend fun requestTakeover(message: String): Boolean = error("Unexpected takeover")
            }
            val handler = SecureBrowserNativeHandler(BrowserModelPort { error("Unexpected model") }, interaction, safetyModeProvider = { mode })
            val input = BrowserActionAuthorizationRequest(actionName = "input_text", summary = "First name")
            suspend fun authorize(): BrowserActionAuthorizationResponse {
                val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
                assertTrue(response.success)
                return json.decodeFromString(BrowserActionAuthorizationResponse.serializer(), response.payload)
            }
            assertTrue(authorize().allowed)
            // Prior consent is not a cached permit for the next field mutation.
            val declined = authorize()
            assertFalse(declined.allowed)
            assertEquals(BrowserActionClass.ORDINARY_INPUT, declined.actionClass)
            assertEquals(2, confirmations)
        }
    }

    @Test
    fun `disable while confirmation is pending prevents stale authorization`() = runBlocking {
        val events = mutableListOf<String>()
        val interaction = object : BrowserUserInteraction {
            override suspend fun confirm(message: String): Boolean {
                AgentRuntimeGate.setEnabled(false)
                return true
            }
            override suspend fun ask(question: String): String = error("Unexpected question")
            override suspend fun requestTakeover(message: String): Boolean = error("Unexpected takeover")
        }
        val handler = SecureBrowserNativeHandler(BrowserModelPort { error("Unexpected model") }, interaction,
            BrowserEventSink { _, payload -> events.add(payload) })
        val input = BrowserActionAuthorizationRequest(actionName = "input_text", summary = "First name")
        val response = handler.handle(request(PageAgentRequestType.AUTHORIZE_ACTION, json.encodeToString(input)))
        // Emergency stop revokes even approval returned by a previously opened native dialog.
        assertFalse(response.success)
        assertEquals("AGENT_DISABLED", response.errorCode)
        assertTrue(events.isEmpty())
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
