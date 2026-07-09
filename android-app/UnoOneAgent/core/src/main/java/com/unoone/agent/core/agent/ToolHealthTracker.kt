package com.unoone.agent.core.agent

/**
 * Pure-JVM self-heal policy for the agent: (a) a rolling per-tool health tracker that flags a tool as
 * flaky after enough recent failures, and (b) a brain-reload decision that fires after enough
 * consecutive inference failures (the brain closes itself on a 30s timeout and nothing reloads it
 * until onResume — this is the auto-recovery that was missing).
 *
 * The JVM-tested half is this file. The actual brain reload (`AgentOrchestrator.loadLlmModel`) and the
 * spoken/timeline diagnostic are device-time-only; the orchestrator calls [shouldReload] /
 * [ToolHealthTracker.isFlaky] and acts. No wall-clock is used — outcomes are a rolling deque, so the
 * contract is deterministic and fully unit-testable.
 */
class ToolHealthTracker(
    private val windowSize: Int = 10,
    private val flakyThreshold: Int = 3
) {
    private val recent = mutableMapOf<String, ArrayDeque<Boolean>>()

    /** Records a tool execution outcome (`success` true/false) into the rolling window. */
    fun record(tool: String, success: Boolean) {
        val deque = recent.getOrPut(tool) { ArrayDeque() }
        deque.addLast(success)
        while (deque.size > windowSize) deque.removeFirst()
    }

    /** Number of failures in the current window for [tool]. */
    fun failureCount(tool: String): Int =
        (recent[tool] ?: emptyList()).count { !it }

    /** True when [tool] has at least [flakyThreshold] failures in its recent window. */
    fun isFlaky(tool: String): Boolean = failureCount(tool) >= flakyThreshold

    /** Resets a tool's window (e.g. after a successful recovery). */
    fun reset(tool: String) { recent.remove(tool) }
}

object BrainHealthPolicy {
    /** Reload the brain after this many consecutive inference failures (not-loaded / timeout). */
    const val RELOAD_THRESHOLD: Int = 2

    /** True when [consecutiveInferenceFailures] warrants an automatic brain reload. */
    fun shouldReload(consecutiveInferenceFailures: Int): Boolean =
        consecutiveInferenceFailures >= RELOAD_THRESHOLD
}