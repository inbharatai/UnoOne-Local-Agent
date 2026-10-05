package com.unoone.agent.core.agent

import com.unoone.agent.core.model.Result
import org.junit.Assert.*
import org.junit.Test

class ExecutionOutcomePolicyTest {
    @Test fun mixedResultsArePartialNotSuccess() {
        val results = listOf(Result.Success("saved"), Result.Error("failed"))
        assertEquals("partial", ExecutionOutcomePolicy.compoundStatus(results))
        assertTrue(ExecutionOutcomePolicy.combine(results) is Result.Error)
    }
    @Test fun deterministicSuccessIsPreserved() {
        val results = listOf(Result.Success("one"), Result.Success("two"))
        assertEquals("success", ExecutionOutcomePolicy.compoundStatus(results))
        assertEquals(Result.Success("one; two"), ExecutionOutcomePolicy.combine(results))
    }
    @Test fun emptyAndAllFailedAreFailures() {
        assertEquals("failed", ExecutionOutcomePolicy.compoundStatus(emptyList()))
        assertTrue(ExecutionOutcomePolicy.combine(emptyList()) is Result.Error)
        assertEquals("failed", ExecutionOutcomePolicy.compoundStatus(listOf(Result.Error("bad"))))
    }
    @Test fun plannerSpeechNeverProvesWorkflowSuccess() {
        assertEquals("responded", ExecutionOutcomePolicy.loopStatus(StopReason.SPOKE_RESPONSE))
        assertEquals("unverified", ExecutionOutcomePolicy.loopStatus(StopReason.NO_PLAN))
        assertEquals("failed", ExecutionOutcomePolicy.loopStatus(StopReason.PLANNER_ERROR))
        assertEquals("failed", ExecutionOutcomePolicy.loopStatus(StopReason.STALL_DETECTED))
        assertEquals("limit", ExecutionOutcomePolicy.loopStatus(StopReason.MAX_STEPS))
        StopReason.values().forEach { assertNotEquals("success", ExecutionOutcomePolicy.loopStatus(it)) }
    }
}
