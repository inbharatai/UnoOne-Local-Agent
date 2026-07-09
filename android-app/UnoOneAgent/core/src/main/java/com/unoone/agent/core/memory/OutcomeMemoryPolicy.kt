package com.unoone.agent.core.memory

/**
 * Pure-JVM policy for outcome-learned memory: turning a history of `(command → tool → success/failure)`
 * into a short, planner-facing hint so the model avoids repeating a known-bad action and leans on a
 * known-good one for similar requests.
 *
 * This is the JVM-tested control half of the feature. The Room persistence + retrieval plumbing lives
 * in `:memory` ([com.unoone.agent.memory.MemoryModule]); the LiteRT-LM that consumes the rendered hint
 * is device-time-only. Nothing here touches Android or Room, so the signature + matching + rendering
 * contract is fully unit-testable.
 */
data class OutcomeRecord(
    val signature: String,
    val tool: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val updatedAt: Long = 0
)

object OutcomeMemoryPolicy {

    /** Tiny stopword set so "open chrome" and "open whatsapp" don't collapse to the same signature. */
    private val stopwords = setOf(
        "a", "an", "the", "please", "can", "you", "to", "my", "me", "for", "and", "this", "that",
        "is", "are", "of", "in", "on", "at", "now", "go", "do", "i", "want", "need",
        "about", "with", "from", "into", "your", "just", "some"
    )

    /**
     * Normalized prompt signature: lowercase, alphanumeric tokens, stopwords + single-char tokens
     * dropped, whitespace-collapsed. Two commands with the same meaningful tokens map to the same
     * signature (e.g. "open chrome" / "Open Chrome please" → "open chrome").
     */
    fun signature(command: String): String =
        command.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopwords && it.length > 1 }
            .joinToString(" ")

    /** Tokens of a signature, for overlap matching. */
    private fun tokens(sig: String): Set<String> = sig.split(" ").filter { it.isNotBlank() }.toSet()

    /**
     * Picks the most relevant prior outcomes for [command]: those sharing at least one meaningful
     * token, ranked by overlap (desc) then recency (desc), capped at [maxN]. Failures rank above
     * successes at equal overlap — a known-bad outcome is the more useful signal to surface.
     */
    fun relevantOutcomes(
        command: String,
        outcomes: List<OutcomeRecord>,
        maxN: Int = 3
    ): List<OutcomeRecord> {
        val cmdTokens = tokens(signature(command))
        if (cmdTokens.isEmpty()) return emptyList()
        return outcomes
            .map { rec -> rec to tokens(rec.signature).intersect(cmdTokens).size }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<OutcomeRecord, Int>> { it.second }
                .thenByDescending { !it.first.success }   // failures first at equal overlap
                .thenByDescending { it.first.updatedAt })
            .take(maxN)
            .map { it.first }
    }

    /**
     * Renders the chosen outcomes into a compact hint for the planner, or "" when none. Failures are
     * framed as warnings ("avoid: … failed because …"); successes as confirmations ("worked: …").
     * The planner is told these are prior observations, not commands.
     */
    fun render(outcomes: List<OutcomeRecord>): String {
        if (outcomes.isEmpty()) return ""
        return outcomes.joinToString("; ") { rec ->
            val verb = if (rec.success) "worked" else "avoid"
            val reason = if (!rec.success && !rec.errorMessage.isNullOrBlank()) " (${rec.errorMessage})" else ""
            "prior $verb: ${rec.tool}$reason"
        }
    }
}