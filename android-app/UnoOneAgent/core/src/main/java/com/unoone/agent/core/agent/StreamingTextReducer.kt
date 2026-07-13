package com.unoone.agent.core.agent

/**
 * Pure-JVM streaming reducer: turns a sequence of partial text snapshots arriving from a streaming
 * LLM into (a) the running full text and (b) the incremental delta to surface per snapshot. Robust
 * to both cumulative semantics (each snapshot is the full text generated so far — LiteRT-LM's
 * documented `Flow<Message>` behavior, where every emission carries the complete-so-far contents)
 * and delta semantics (each snapshot is only the newly generated tokens), detected by checking
 * whether the new snapshot extends the current accumulated text.
 *
 * This is the JVM-tested control half of streaming inference ([com.unoone.agent.localbrain.GemmaPlanner]
 * calls [onSnapshot] per streamed `Message` and forwards the returned delta to the UI/timeline). The
 * LiteRT-LM `Flow` that feeds it, and the partial-text-to-TTS surfacing, are device-time-only — they
 * cannot be JVM-tested because `litertlm-android` bytecode is newer than the JDK 17 test JVM can load.
 *
 * Contract notes:
 * - The final accumulated text ([fullText]) is correct under both semantics; only the *chunking* of
 *   deltas differs, so the UI always converges to the right string.
 * - A snapshot that does not extend the current text is treated as a delta and appended (the safe
 *   fail-forward when the model resets or emits non-cumulative tokens).
 */
class StreamingTextReducer {
    private val builder = StringBuilder()

    /**
     * Accepts one streamed [snapshot] and returns the new text to surface (the delta since the last
     * call). Updates internal state. Returns "" when the snapshot adds nothing new.
     */
    fun onSnapshot(snapshot: String): String {
        if (snapshot.isEmpty()) return ""
        val current = builder.toString()
        return if (snapshot.startsWith(current)) {
            // Cumulative: snapshot is the full text so far → delta is the new suffix.
            val delta = snapshot.substring(current.length)
            builder.clear()
            builder.append(snapshot)
            delta
        } else if (current.isEmpty()) {
            // First snapshot.
            builder.append(snapshot)
            snapshot
        } else {
            // Not an extension (delta semantics or a model reset): append as a delta.
            builder.append(snapshot)
            snapshot
        }
    }

    /** The full text accumulated from all snapshots so far. */
    fun fullText(): String = builder.toString()

    /** Resets the accumulator (e.g. before a fresh command). */
    fun reset() {
        builder.clear()
    }
}