package com.unoone.agent.localbrain

import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.eval.EvalPromptSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces that what Gemma 4 is told it can call matches what UnoOne will accept.
 *
 * A tool advertised but absent from [CanonicalToolRegistry] would be rejected after wasting model
 * output. A canonical tool absent from the prompt would become an unreachable capability. The exact
 * bidirectional equality is therefore a safety and reliability invariant.
 */
class ToolRegistryAgreementTest {

    @Test
    fun evaluationCommandsExposeTheirExpectedCanonicalTool() {
        assertEquals(29, CanonicalToolRegistry.names.size)
        EvalPromptSet.cases.forEach { case ->
            val routed = PlannerToolRouter.schemasFor(case.prompt).map { it.name }.toSet()
            assertTrue("${case.id} must expose ${case.expectedTool}; routed=$routed", case.expectedTool in routed)
        }
    }

    @Test
    fun everyRoutedToolIsCanonicalAndTurnsStayBounded() {
        EvalPromptSet.cases.forEach { case ->
            val routed = PlannerToolRouter.schemasFor(case.prompt)
            assertTrue("No command should expose more than seven tools", routed.size <= 7)
            assertTrue(CanonicalToolRegistry.names.containsAll(routed.map { it.name }))
        }
    }

    @Test
    fun prohibitedSecretsExposeOnlySpeech() {
        listOf(
            "enter OTP 123456",
            "type my password hunter2",
            "transfer 5000 rupees by UPI"
        ).forEach { prompt ->
            assertEquals(
                setOf("speak_response"),
                PlannerToolRouter.schemasFor(prompt).map { it.name }.toSet()
            )
        }
    }

    @Test
    fun requiredFieldsAndBlindDirectionAreDeterministic() {
        assertEquals(setOf("speak_response"), names("draft an email with subject status"))
        assertEquals(setOf("speak_response"), names("schedule a dentist appointment"))
        assertEquals(setOf("speak_response", "detect_objects"), names("ब्लाइंड मोड चालू करो"))
        assertEquals(setOf("speak_response", "deactivate_blind_aid"), names("ब्लाइंड मोड बंद करो"))
        assertEquals(setOf("speak_response"), names("open"))
    }

    private fun names(command: String): Set<String> =
        PlannerToolRouter.schemasFor(command).map { it.name }.toSet()
}
