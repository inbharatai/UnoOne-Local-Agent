package com.unoone.agent.core.model

/** Pure configuration passed to LiteRT-LM by the Android runtime. */
data class E4bRuntimeBudget(
    val contextTokens: Int,
    val outputTokens: Int
) {
    init {
        require(contextTokens in 256..2_048) { "E4B mobile context must not exceed 2048 tokens" }
        require(outputTokens in 1 until contextTokens) { "Output budget must fit inside context" }
    }
}
/** The checked mobile budgets. Prompt text is reduced to fit these values; they are not advisory. */
object E4bRuntimeBudgets {
    const val MOBILE_CONTEXT_TOKENS = 2_048
    const val PHONE_OUTPUT_TOKENS = 256
    // Voice answers should reach TTS quickly. Ninety-six tokens is enough for the enforced
    // one-or-two-sentence response while preventing multi-paragraph, 15+ second decodes.
    const val CHAT_OUTPUT_TOKENS = 96
    const val PAGE_AGENT_OUTPUT_TOKENS = 384

    fun phone(spec: BrainModelSpec): E4bRuntimeBudget =
        build(spec, PHONE_OUTPUT_TOKENS)

    fun chat(spec: BrainModelSpec): E4bRuntimeBudget =
        build(spec, CHAT_OUTPUT_TOKENS)

    fun pageAgent(spec: BrainModelSpec): E4bRuntimeBudget =
        build(spec, PAGE_AGENT_OUTPUT_TOKENS)

    private fun build(spec: BrainModelSpec, outputTokens: Int): E4bRuntimeBudget {
        require(spec.id == BrainModelId.GEMMA_4_E4B) { "Only Gemma 4 E4B is supported" }
        require(spec.defaultContextTokens == MOBILE_CONTEXT_TOKENS) {
            "E4B spec context changed without mobile qualification"
        }
        return E4bRuntimeBudget(spec.defaultContextTokens, outputTokens)
    }
}
