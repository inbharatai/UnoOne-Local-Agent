package com.unoone.agent.core.eval

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the calibration scorer. The device harness is what produces the real Gemma
 * numbers; these tests pin the scoring contract so a "pass" actually means "right tool + right args"
 * and a "fail" is diagnosed as tool-miss vs arg-miss, not collapsed.
 */
class EvalScorerTest {

    private fun call(tool: String, vararg args: Pair<String, String>): ToolCall =
        ToolCall(tool, buildJsonObject(args))

    private fun buildJsonObject(args: Array<out Pair<String, String>>): JsonObject {
        val map = args.associate { (k, v) -> k to JsonPrimitive(v) }
        return JsonObject(map)
    }

    private val case = EvalCase("t1", "open whatsapp", "open_app", mapOf("app_name" to "whatsapp"))

    @Test
    fun exactToolAndArgsScoresCorrect() {
        val verdict = EvalScorer.score(case, call("open_app", "app_name" to "whatsapp"))
        assertTrue("must be correct", verdict.correct)
        assertTrue(verdict.toolMatch)
        assertTrue(verdict.argMatches.getValue("app_name"))
    }

    @Test
    fun wrongToolIsIncorrectButReported() {
        val verdict = EvalScorer.score(case, call("open_chrome"))
        assertFalse(verdict.correct)
        assertFalse(verdict.toolMatch)
        assertEquals("open_chrome", verdict.actualTool)
    }

    @Test
    fun rightToolMissingArgIsToolMatchButIncorrect() {
        val verdict = EvalScorer.score(case, call("open_app"))
        assertTrue("tool still matches", verdict.toolMatch)
        assertFalse(verdict.argMatches.getValue("app_name"))
        assertFalse(verdict.correct)
    }

    @Test
    fun rightToolWrongArgValueIsToolMatchButIncorrect() {
        val verdict = EvalScorer.score(case, call("open_app", "app_name" to "telegram"))
        assertTrue(verdict.toolMatch)
        assertFalse("wrong value must fail arg", verdict.argMatches.getValue("app_name"))
        assertFalse(verdict.correct)
    }

    @Test
    fun argMatchIsCaseInsensitiveAndSubstringTolerant() {
        val case2 = EvalCase("t2", "open chrome", "open_chrome", emptyMap())
        val noteCase = EvalCase("t3", "create note titled shopping list with milk and bread",
            "create_note", mapOf("title" to "shopping list", "content" to "milk and bread"))
        // Model paraphrased "Shopping List" with caps and trailing punctuation.
        val verdict = EvalScorer.score(
            noteCase,
            call("create_note", "title" to "Shopping List.", "content" to "buy milk and bread today")
        )
        assertTrue("substring + ignoreCase must match", verdict.correct)
        // empty-expected case
        val summariseCase = EvalCase("t4", "summarize: fox", "summarize_text", mapOf("text" to ""))
        assertTrue(EvalScorer.score(summariseCase, call("summarize_text", "text" to "the fox jumps")).correct)
        assertFalse(EvalScorer.score(summariseCase, call("summarize_text")).correct)
    }

    @Test
    fun nullActualScoresFullyIncorrect() {
        val verdict = EvalScorer.score(case, null)
        assertFalse(verdict.toolMatch)
        assertFalse(verdict.correct)
        assertEquals(null, verdict.actualTool)
    }

    @Test
    fun summarizeCountsToolsAndFullCorrectSeparately() {
        val cases = listOf(
            EvalCase("a", "p1", "open_chrome"),
            EvalCase("b", "p2", "open_app", mapOf("app_name" to "whatsapp")),
            EvalCase("c", "p3", "read_screen")
        )
        val actuals = listOf(
            call("open_chrome"),                 // full correct
            call("open_app", "app_name" to "wa"),// tool match, arg miss
            call("open_camera")                  // tool miss
        )
        val summary = EvalScorer.scoreAll(cases, actuals)
        assertEquals(3, summary.total)
        assertEquals(2, summary.toolMatches)
        assertEquals(1, summary.fullyCorrect)
        assertEquals(1.0 / 3.0, summary.accuracy, 1e-9)
        assertEquals(2.0 / 3.0, summary.toolAccuracy, 1e-9)
    }

    @Test
    fun emptySummaryDoesNotDivideByZero() {
        val summary = EvalScorer.summarize(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0.0, summary.accuracy, 1e-9)
        assertEquals(0.0, summary.toolAccuracy, 1e-9)
    }

    @Test
    fun promptSetIsNotEmptyAndCasesHaveUniqueIds() {
        assertTrue("prompt set must have cases", EvalPromptSet.cases.isNotEmpty())
        val ids = EvalPromptSet.cases.map { it.id }
        assertEquals("case ids must be unique", ids.size, ids.toSet().size)
        // Every case must name a tool (the whole point of the set).
        EvalPromptSet.cases.forEach { c ->
            assertTrue("case ${c.id} must have a non-blank expectedTool", c.expectedTool.isNotBlank())
        }
    }
}