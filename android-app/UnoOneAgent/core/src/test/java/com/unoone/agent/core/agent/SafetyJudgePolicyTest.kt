package com.unoone.agent.core.agent

import com.unoone.agent.core.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the safety-judge escalation contract: the second model pass may only ever
 * raise the risk tier, never lower it. The inference that produces a [SafetyVerdict] is
 * device-time verified; these tests pin the merge rule that protects the keyword filter from being
 * weakened by the judge.
 */
class SafetyJudgePolicyTest {

    @Test
    fun unsafeEscalatesToBlockFromAnyLowerTier() {
        for (current in listOf(RiskLevel.DIRECT, RiskLevel.CONFIRM, RiskLevel.STRONG_CONFIRM)) {
            assertEquals(
                "UNSAFE from $current must escalate to BLOCK",
                RiskLevel.BLOCK,
                SafetyJudgePolicy.escalate(current, SafetyVerdict.UNSAFE)
            )
        }
    }

    @Test
    fun unsafeDoesNotExceedBlock() {
        assertEquals(
            RiskLevel.BLOCK,
            SafetyJudgePolicy.escalate(RiskLevel.BLOCK, SafetyVerdict.UNSAFE)
        )
    }

    @Test
    fun needsConfirmEscalatesDirectToConfirm() {
        assertEquals(
            RiskLevel.CONFIRM,
            SafetyJudgePolicy.escalate(RiskLevel.DIRECT, SafetyVerdict.NEEDS_CONFIRM)
        )
    }

    @Test
    fun needsConfirmDoesNotLowerStrongConfirmOrBlock() {
        // The judge said "needs confirm" but the keyword filter already said STRONG_CONFIRM — the
        // higher tier wins. The judge cannot de-escalate.
        assertEquals(
            RiskLevel.STRONG_CONFIRM,
            SafetyJudgePolicy.escalate(RiskLevel.STRONG_CONFIRM, SafetyVerdict.NEEDS_CONFIRM)
        )
        assertEquals(
            RiskLevel.BLOCK,
            SafetyJudgePolicy.escalate(RiskLevel.BLOCK, SafetyVerdict.NEEDS_CONFIRM)
        )
    }

    @Test
    fun safeNeverLowersTheTier() {
        for (current in RiskLevel.entries) {
            assertEquals(
                "SAFE from $current must not change the tier",
                current,
                SafetyJudgePolicy.escalate(current, SafetyVerdict.SAFE)
            )
        }
    }

    @Test
    fun uncertainNeverLowersTheTier() {
        // An unparseable judge verdict fails safe: the keyword filter's decision stands unchanged.
        for (current in RiskLevel.entries) {
            assertEquals(current, SafetyJudgePolicy.escalate(current, SafetyVerdict.UNCERTAIN))
        }
    }

    @Test
    fun safeDoesNotSaveABlockedAction() {
        // The most important contract: the judge saying "SAFE" cannot rescue an action the keyword
        // filter blocked. BLOCK stays BLOCK.
        assertEquals(
            RiskLevel.BLOCK,
            SafetyJudgePolicy.escalate(RiskLevel.BLOCK, SafetyVerdict.SAFE)
        )
    }

    // === shouldRun() — when the judge is invoked at all ===

    @Test
    fun shouldRunSkipsDirectToolsEvenWhenEverythingElseIsEnabled() {
        // Regression for the "speak_response → CONFIRM" popup: harmless DIRECT tools must not
        // incur a second LLM judge inference (and its "stricter verdict" escalation bias).
        assertFalse(
            "DIRECT tools must skip the judge",
            SafetyJudgePolicy.shouldRun(
                judgeEnabled = true,
                judgeFlagEnabled = true,
                modelLoaded = true,
                riskLevel = RiskLevel.DIRECT
            )
        )
    }

    @Test
    fun shouldRunsForConfirmAndStrongConfirmAndBlockWhenEnabled() {
        for (level in listOf(RiskLevel.CONFIRM, RiskLevel.STRONG_CONFIRM, RiskLevel.BLOCK)) {
            assertTrue(
                "$level must run the judge when enabled + model loaded",
                SafetyJudgePolicy.shouldRun(
                    judgeEnabled = true,
                    judgeFlagEnabled = true,
                    modelLoaded = true,
                    riskLevel = level
                )
            )
        }
    }

    @Test
    fun shouldRunFalseWhenSecurityLevelBelowStandard() {
        assertFalse(SafetyJudgePolicy.shouldRun(false, true, true, RiskLevel.CONFIRM))
    }

    @Test
    fun shouldRunFalseWhenJudgeFlagOff() {
        assertFalse(SafetyJudgePolicy.shouldRun(true, false, true, RiskLevel.CONFIRM))
    }

    @Test
    fun shouldRunFalseWhenBrainNotLoaded() {
        assertFalse(SafetyJudgePolicy.shouldRun(true, true, false, RiskLevel.CONFIRM))
    }
}