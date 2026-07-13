package com.unoone.agent.core.agent

import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reason → Act → Observe control for the on-device Gemma brain.
 *
 * UnoOne's brain proposes **one tool call per turn** (manual tool calling). The
 * [ReAct loop][com.unoone.agent.AgentOrchestrator] lets the model *see* each tool's result and
 * propose the next step, so a single user command ("find my note about the trip and summarize it")
 * can chain `search_notes` → `summarize_text` → `speak_response` instead of stopping at the first
 * call. Every step still flows through the full safety pipeline (permissions → risk → block →
 * confirm → execute → audit); this controller only decides *whether to ask the model for another
 * step* — it never executes anything itself.
 *
 * This object is **pure dependency-free logic** so the termination rules can be unit-tested on the
 * JVM. The actual LiteRT-LM multi-turn inference (feeding a `Content.ToolResponse` back into the
 * conversation) lives in [com.unoone.agent.localbrain.GemmaPlanner] and is device-time verified,
 * because `litertlm-android` ships bytecode newer than the JDK 17 test JVM can load.
 */
object ReActLoopController {

    /**
     * Maximum tool executions per single user command, **including** the first (non-continuation)
     * call. With [MAX_STEPS] = 3 the model gets the initial call plus up to 2 follow-ups — enough
     * for a genuine Reason→Act→Observe→Act→Observe chain without unbounded on-device inference.
     */
    const val MAX_STEPS: Int = 3

    /**
     * Tools whose *result* the model can usefully reason over. The loop engages only when the
     * model's first proposed call is in this set; for one-shot side-effect tools (`open_app`,
     * `create_note`, `send_whatsapp`, …) the model has nothing to react to, so continuing would
     * only add a second inference's latency and risk without value. The model still decides when to
     * stop (it emits `speak_response` or the loop hits [MAX_STEPS]); this set just gates *entry*.
     */
    val observationTools: Set<String> = setOf(
        "search_notes", "summarize_text", "web_search", "read_screen", "ocr_screen",
        "detect_objects", "voice_recording", "check_calendar"
    )

    /** True iff the loop should engage after the model's first proposed [firstTool] call. */
    fun shouldEngage(firstTool: String): Boolean = firstTool in observationTools

    /**
     * Decides what to do after the model is asked for a next step.
     *
     * @param stepsExecuted how many tool calls have already been executed (the first call counts),
     *   so this is `>= 1` when called from inside the loop.
     * @param lastExecutedCall the most recent tool call that was executed and produced the
     *   observation just fed back to the model (used for stall detection).
     * @param proposal the model's response to "here is the result of your last call; what next?" —
     *   `null` if the planner could not be asked (e.g. brain unloaded), a [Result.Error] if the
     *   planner rejected the call (unknown tool / malformed args / inference failure), or a
     *   [Result.Success] with the next proposed [ToolCall].
     */
    fun decide(
        stepsExecuted: Int,
        lastExecutedCall: ToolCall?,
        proposal: Result<ToolCall>?
    ): LoopDecision {
        // The planner could not be reached at all (brain unloaded mid-loop) → stop, speak last obs.
        if (proposal == null) return LoopDecision.Stop(StopReason.NO_PLAN)

        if (proposal is Result.Error) {
            return LoopDecision.Stop(StopReason.PLANNER_ERROR, plannerErrorText = proposal.message)
        }

        val call = (proposal as Result.Success).data

        // The model chose to speak → terminal. We do NOT execute speak_response as a tool here
        // (that would double-speak); the loop caller speaks the text directly.
        if (call.tool == "speak_response") {
            return LoopDecision.Stop(StopReason.SPOKE_RESPONSE, spokenText = call.spokenText())
        }

        // Stall: the model re-proposed the identical call it just executed (same tool + same args).
        // Continuing would loop forever; stop and surface the last observation.
        if (lastExecutedCall != null &&
            lastExecutedCall.tool == call.tool &&
            lastExecutedCall.args == call.args
        ) {
            return LoopDecision.Stop(StopReason.STALL_DETECTED)
        }

        // Hard ceiling on on-device inference per command.
        if (stepsExecuted >= MAX_STEPS) {
            return LoopDecision.Stop(StopReason.MAX_STEPS)
        }

        return LoopDecision.Continue(call)
    }
}

/** What the [ReActLoopController] says to do next. */
sealed class LoopDecision {
    /** Ask the safety pipeline to execute [call], then feed its result back to the model. */
    data class Continue(val call: ToolCall) : LoopDecision()

    /**
     * Stop the loop. [reason] explains why; exactly one of [spokenText] / [plannerErrorText] is
     * non-null when meaningful. The caller speaks [spokenText] (or the last observation when null)
     * so the user always hears an outcome.
     */
    data class Stop(
        val reason: StopReason,
        val spokenText: String? = null,
        val plannerErrorText: String? = null
    ) : LoopDecision()
}

/** Why the ReAct loop stopped. */
enum class StopReason {
    /** The model proposed `speak_response` — the natural end of a reasoning chain. */
    SPOKE_RESPONSE,

    /** The planner returned nothing (brain unloaded mid-loop). */
    NO_PLAN,

    /** The planner rejected its own output (unknown tool / malformed args / inference failure). */
    PLANNER_ERROR,

    /** The model re-proposed the identical call it just made — detect-and-break the cycle. */
    STALL_DETECTED,

    /** The [ReActLoopController.MAX_STEPS] ceiling was reached. */
    MAX_STEPS
}

/**
 * Extracts the `text` argument of a `speak_response` call, or a neutral fallback if absent.
 * Kept here (core) so the controller does not depend on the JSON helpers of higher modules.
 */
private fun ToolCall.spokenText(): String =
    (args["text"] as? JsonPrimitive)?.content ?: "Done."