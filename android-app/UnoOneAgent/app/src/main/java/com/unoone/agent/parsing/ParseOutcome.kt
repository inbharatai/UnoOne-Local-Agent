package com.unoone.agent.parsing

import com.unoone.agent.core.model.ToolCall

/**
 * The result of parsing a command, **with provenance** — which path produced the tool call.
 *
 * The ReAct loop ([com.unoone.agent.AgentOrchestrator]) may only continue a conversation with tool
 * observations when the call came from the LLM: a rule-based match never started a LiteRT-LM
 * conversation for this command, so feeding an observation back would be nonsensical. [parseAsync]
 * returns a plain [ToolCall]? for callers that do not care (the read-only self-test probe); this
 * sealed type carries the origin the loop needs.
 */
sealed class ParseOutcome {
    /** The [RuleBasedParser] fast path matched. No LLM conversation was started for this command. */
    data class Rule(val toolCall: ToolCall) : ParseOutcome()

    /** The loaded Gemma brain planned this call; the conversation is live and can be continued. */
    data class Llm(val toolCall: ToolCall) : ParseOutcome()

    /** Neither rules nor the LLM produced a plan. */
    object None : ParseOutcome()

    /** Convenience: the tool call regardless of origin, or null for [None]. */
    fun toolCallOrNull(): ToolCall? = when (this) {
        is Rule -> toolCall
        is Llm -> toolCall
        None -> null
    }
}