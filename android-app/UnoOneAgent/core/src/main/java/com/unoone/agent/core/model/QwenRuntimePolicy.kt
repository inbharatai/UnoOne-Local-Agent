package com.unoone.agent.core.model

/** Bounded app policy, not a mutation of the downloaded/hash-bound MNN config. */
data class QwenRuntimePolicy(
    val contextTokens: Int = 2_048,
    val outputTokens: Int = 256
) {
    init {
        require(contextTokens == 2_048 || contextTokens == 4_096)
        require(outputTokens in 1..256)
    }

    /** Context admission must ALSO be enforced using the native tokenizer before generation. */
    fun nativeOverrideJson(): String =
        """{"backend_type":"cpu","max_new_tokens":$outputTokens,"jinja":{"context":{"enable_thinking":false}}}"""

    fun receipt(): QwenRuntimePolicyReceipt = QwenRuntimePolicyReceipt(
        contextTokens = contextTokens,
        outputTokens = outputTokens,
        resolvedOverrideJson = nativeOverrideJson()
    )
}

/** Requested/resolved policy only: native loader acknowledgement is separately required. */
data class QwenRuntimePolicyReceipt(
    val contextTokens: Int,
    val outputTokens: Int,
    val resolvedOverrideJson: String,
    val backend: String = "cpu",
    val thinkingEnabled: Boolean = false
)
