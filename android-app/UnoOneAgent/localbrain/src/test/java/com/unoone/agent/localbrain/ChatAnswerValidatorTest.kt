package com.unoone.agent.localbrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAnswerValidatorTest {
    @Test
    fun rejectsEmptyAndPunctuationOnlyModelCompletions() {
        listOf(null, "", "   ", ".", "..", "?!", "।").forEach { output ->
            assertFalse("must reject <$output>", ChatAnswerValidator.assess(output).isValid)
        }
    }

    @Test
    fun rejectsTruncatedAndToolSyntaxOutput() {
        assertFalse(ChatAnswerValidator.assess("x").isValid)
        assertFalse(ChatAnswerValidator.assess("{\"tool_calls\":[]}").isValid)
        assertFalse(ChatAnswerValidator.assess("<start_of_turn>model").isValid)
        assertFalse(ChatAnswerValidator.assess("aaaaaa").isValid)
    }

    @Test
    fun acceptsMeaningfulEnglishHindiAndNumericAnswers() {
        listOf(
            "SAT is a standardized test used in college admissions.",
            "SAT एक मानकीकृत परीक्षा है।",
            "42",
            "Yes."
        ).forEach { output ->
            assertTrue("must accept <$output>", ChatAnswerValidator.assess(output).isValid)
        }
    }

    @Test
    fun normalizesWhitespaceBeforeReturningAnswer() {
        val result = ChatAnswerValidator.assess("  SAT   is\nan assessment.  ")
        assertTrue(result.isValid)
        assertEquals("SAT is an assessment.", result.normalized)
    }
}
