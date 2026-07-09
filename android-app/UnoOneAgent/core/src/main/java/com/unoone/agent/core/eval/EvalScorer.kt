package com.unoone.agent.core.eval

import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One scored calibration case. [correct] is true only when the model picked the right tool AND every
 * checked argument matched. [argMatches] is per-key so a profile can be diagnosed as "right tool,
 * wrong args" rather than collapsed to a single fail.
 */
data class EvalVerdict(
    val caseId: String,
    val prompt: String,
    val expectedTool: String,
    val actualTool: String?,
    val toolMatch: Boolean,
    val argMatches: Map<String, Boolean>,
    val correct: Boolean
)

/**
 * Aggregate over a run of the prompt set. [accuracy] is the strict per-case score (correct / total);
 * [toolAccuracy] is tool-selection alone (tool matches / total) — reported separately because a
 * profile may select the right tool but extract args poorly, which matters for the migration call.
 */
data class EvalSummary(
    val total: Int,
    val toolMatches: Int,
    val fullyCorrect: Int,
    val perCase: List<EvalVerdict>,
    val accuracy: Double,
    val toolAccuracy: Double
) {
    override fun toString(): String = buildString {
        appendLine("EvalSummary: $fullyCorrect / $total fully correct (${(accuracy * 100).toInt()}%), " +
            "$toolMatches / $total tool-match (${(toolAccuracy * 100).toInt()}%)")
        perCase.forEach { v ->
            val mark = if (v.correct) "OK " else if (v.toolMatch) "arg" else "MISS"
            appendLine("  [$mark] ${v.caseId}: expected=${v.expectedTool} actual=${v.actualTool ?: "null"}")
            v.argMatches.filter { !it.value }.forEach { (k, ok) ->
                if (!ok) appendLine("        arg '$k' mismatch")
            }
        }
    }
}

/**
 * Pure-JVM scorer for the calibration prompt set. No device, no LiteRT-LM — the device harness feeds
 * it raw [ToolCall]s (or null on load/parse failure) and reads the numbers. Arg matching is lenient
 * by design: a blank expected value means "present and non-empty"; a non-blank expected value must be
 * contained in the model's value (case-insensitive, trimmed) so paraphrased prompts don't score as
 * failures on wording alone.
 */
object EvalScorer {

    fun score(case: EvalCase, actual: ToolCall?): EvalVerdict {
        val toolMatch = actual != null && actual.tool == case.expectedTool
        val argMatches = case.expectedArgs.mapValues { (name, expected) ->
            val element = actual?.args?.get(name)
            val actualStr = (element as? JsonPrimitive)?.contentOrNull
                ?: element?.toString()
                ?: ""
            if (expected.isBlank()) actualStr.isNotBlank()
            else actualStr.trim().contains(expected.trim(), ignoreCase = true)
        }
        val correct = toolMatch && argMatches.values.all { it }
        return EvalVerdict(
            caseId = case.id,
            prompt = case.prompt,
            expectedTool = case.expectedTool,
            actualTool = actual?.tool,
            toolMatch = toolMatch,
            argMatches = argMatches,
            correct = correct
        )
    }

    fun scoreAll(cases: List<EvalCase>, actuals: List<ToolCall?>): EvalSummary {
        require(cases.size == actuals.size) { "cases (${cases.size}) and actuals (${actuals.size}) must align" }
        val verdicts = cases.zip(actuals).map { (c, a) -> score(c, a) }
        return summarize(verdicts)
    }

    fun summarize(verdicts: List<EvalVerdict>): EvalSummary {
        val total = verdicts.size
        val toolMatches = verdicts.count { it.toolMatch }
        val fullyCorrect = verdicts.count { it.correct }
        return EvalSummary(
            total = total,
            toolMatches = toolMatches,
            fullyCorrect = fullyCorrect,
            perCase = verdicts,
            accuracy = if (total == 0) 0.0 else fullyCorrect.toDouble() / total,
            toolAccuracy = if (total == 0) 0.0 else toolMatches.toDouble() / total
        )
    }
}