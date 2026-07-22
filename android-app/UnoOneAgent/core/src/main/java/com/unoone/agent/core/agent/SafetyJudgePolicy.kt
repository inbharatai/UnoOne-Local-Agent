package com.unoone.agent.core.agent

import com.unoone.agent.core.model.RiskLevel

/**
 * Verdict a safety-judge model pass can return for a proposed action.
 *
 * The judge is a *second* on-device inference (see
 * [com.unoone.agent.localbrain.GemmaPlanner.judgeSafety]) that reviews a tool call + the raw user
 * input and decides whether the action is safe — catching paraphrased harm that the keyword-based
 * [com.unoone.agent.safetyguard.SafetyGuard] can miss (e.g. "wipe everything" instead of "delete
 * all notes"). It is **never** allowed to weaken the existing keyword tier — see
 * [SafetyJudgePolicy.escalate].
 */
enum class SafetyVerdict {
    /** Action is routine and reversible — but this verdict still never lowers the keyword tier. */
    SAFE,

    /** Action has moderate risk; the user should confirm before it runs. */
    NEEDS_CONFIRM,

    /** Action is irreversible / destructive / sensitive, or the input looks like disguised harm. */
    UNSAFE,

    /** The model gave no parseable verdict. Fail-safe: leave the keyword tier unchanged. */
    UNCERTAIN
}

/**
 * Merge a judge [verdict] with the keyword-classified [current] risk. **Only ever escalates** — the
 * judge can raise DIRECT→CONFIRM→STRONG_CONFIRM→BLOCK, but can never lower a tier. This is the
 * hard safety contract: a second model pass must not weaken the deterministic keyword filter.
 *
 * Pure dependency-free logic so the escalation rules are unit-tested on the JVM; the model inference
 * that produces the verdict is device-time verified (litertlm-android bytecode > JDK 17 test JVM).
 */
object SafetyJudgePolicy {

    fun escalate(current: RiskLevel, verdict: SafetyVerdict): RiskLevel {
        // SAFE and UNCERTAIN never change the tier — the judge is not permitted to de-escalate, and
        // an unparseable verdict fails safe by preserving the keyword result.
        val floor: RiskLevel = when (verdict) {
            SafetyVerdict.UNSAFE -> RiskLevel.BLOCK
            SafetyVerdict.NEEDS_CONFIRM -> RiskLevel.CONFIRM
            SafetyVerdict.SAFE, SafetyVerdict.UNCERTAIN -> return current
        }
        return if (floor.level > current.level) floor else current
    }

    /**
     * Decides whether the LLM safety judge should run for a given tool step. Pure so the gating rule
     * is unit-testable without the orchestrator/Hilt/model.
     *
     * The judge is skipped when ANY of:
     *  - the user's security level is below STANDARD ([judgeEnabled] false),
     *  - the global judge flag is off ([judgeFlagEnabled] false),
     *  - the brain is not loaded ([modelLoaded] false; the judge conversation shares the engine),
     *  - the deterministic tier is anything except [RiskLevel.CONFIRM]. DIRECT operations need no
     *    second pass; STRONG_CONFIRM already requires the highest explicit approval; and BLOCK is
     *    already rejected. The judge is useful only in the middle tier, where it can catch disguised
     *    harm and escalate CONFIRM to BLOCK without adding a full model inference before routine
     *    launch or Blind Aid commands.
     *
     * The judge runs for CONFIRM, where escalation materially changes the decision.
     */
    fun shouldRun(
        judgeEnabled: Boolean,
        judgeFlagEnabled: Boolean,
        modelLoaded: Boolean,
        riskLevel: RiskLevel
    ): Boolean = judgeEnabled && judgeFlagEnabled && modelLoaded && riskLevel == RiskLevel.CONFIRM
}
