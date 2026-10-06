package com.unoone.agent.core.task

import com.unoone.agent.core.model.Result

/** Literal presence only: no normalization, case folding, model grader or factual verification. */
object DraftConstraintPolicy {
    data class Checks(val failedPhraseIndexes: List<Int>, val nonblank: Boolean) {
        val passed: Boolean get() = nonblank && failedPhraseIndexes.isEmpty()
        val semanticFactsVerified: Boolean get() = false
        fun metadata(): String = "Native exact checks: ${if (passed) "passed" else "failed"}; " +
            "failed phrase checks (1-based): ${failedPhraseIndexes.joinToString().ifEmpty { "none" }}; " +
            "nonblank=$nonblank. Semantic facts unverified. Draft only; never sent."
    }
    fun check(request: DraftRequest, text: String): Checks = Checks(
        request.requiredPhrases.mapIndexedNotNull { index, phrase -> if (text.contains(phrase)) null else index + 1 },
        text.isNotBlank())
}

/** Android-free production executor, also callable from the real-MNN host probe.
 * generate MUST independently acquire its shared lease and call beforeModelCall on EVERY invocation.
 * Attempt text is memory-only; only checks.metadata() is safe to echo as diagnostics.
 */
object DraftQualityGate {
    data class Attempt(val text: String, val checks: DraftConstraintPolicy.Checks)
    data class Execution(val attempts: List<Attempt>, val error: Result.Error? = null) {
        val last: Attempt? get() = attempts.lastOrNull()
        val outcome: TaskOutcome get() = if (error == null && last?.checks?.passed == true)
            TaskOutcome.RESPONDED else TaskOutcome.NEEDS_USER
    }
    suspend fun execute(request: DraftRequest,
        generate: suspend (prompt: String, requiredPhrases: List<String>) -> Result<String>): Execution {
        val attempts = mutableListOf<Attempt>()
        repeat(2) { index ->
            val prompt = if (index == 0) request.prompt else
                "Revise the draft below to satisfy every separately supplied exact phrase. Return only the revised draft. " +
                "Do not invent facts or send anything.\nOriginal request:\n${request.prompt.take(2200)}\n" +
                "Unverified draft:\n${attempts.last().text.take(1200)}"
            when (val response = generate(prompt, request.requiredPhrases)) {
                is Result.Error -> return Execution(attempts.toList(), response)
                is Result.Success -> {
                    // Check the exact bounded text that will be shown, never discarded suffixes.
                    val text = response.data.take(6000)
                    val attempt = Attempt(text, DraftConstraintPolicy.check(request, text))
                    attempts += attempt
                    if (attempt.checks.passed) return Execution(attempts.toList())
                }
            }
        }
        return Execution(attempts.toList())
    }
}
