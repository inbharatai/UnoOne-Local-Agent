package com.unoone.agent.core.agent

import com.unoone.agent.core.model.Result

/** Execution evidence, not planner prose, determines workflow outcomes. */
object ExecutionOutcomePolicy {
    fun compoundStatus(results: List<Result<String>>): String = when {
        results.isEmpty() -> "failed"
        results.any { it is Result.Error } -> if (results.any { it is Result.Success }) "partial" else "failed"
        else -> "success"
    }

    fun combine(results: List<Result<String>>): Result<String> {
        val successes = results.filterIsInstance<Result.Success<String>>().map { it.data }
        val errors = results.filterIsInstance<Result.Error>().map { it.message }
        return when {
            results.isEmpty() -> Result.Error("Compound produced no executable steps")
            errors.isNotEmpty() -> Result.Error("Compound stopped after ${successes.size} completed step(s): ${errors.joinToString("; ")}. Remaining steps were not run.")
            else -> Result.Success(successes.joinToString("; "))
        }
    }

    fun loopStatus(reason: StopReason): String = when (reason) {
        StopReason.SPOKE_RESPONSE -> "responded"
        StopReason.NO_PLAN -> "unverified"
        StopReason.PLANNER_ERROR, StopReason.STALL_DETECTED -> "failed"
        StopReason.MAX_STEPS -> "limit"
    }
}
