package com.unoone.agent.observability

import com.unoone.agent.core.util.Logger

object Diagnostics {

    private val metrics = mutableMapOf<String, Long>()

    fun recordModelLoadTime(ms: Long) {
        metrics["model_load_ms"] = ms
        Logger.i("Model load time: ${ms}ms")
    }

    fun recordSttLatency(ms: Long) {
        metrics["stt_latency_ms"] = ms
        Logger.i("STT latency: ${ms}ms")
    }

    fun recordTtsLatency(ms: Long) {
        metrics["tts_latency_ms"] = ms
        Logger.i("TTS latency: ${ms}ms")
    }

    fun recordActionResult(tool: String, success: Boolean) {
        val key = "action_${tool}_${if (success) "success" else "failure"}"
        metrics[key] = (metrics[key] ?: 0) + 1
        Logger.i("Action $tool: ${if (success) "success" else "failure"}")
    }

    /**
     * Records one tool execution end-to-end: the wall-clock duration of the executor call plus the
     * success/failure counter (via [recordActionResult]). Called from
     * [com.unoone.agent.AgentOrchestrator.runValidatedToolCall] so every validated tool — including
     * each step of a compound/skill — is instrumented.
     */
    fun recordToolExecution(tool: String, durationMs: Long, success: Boolean) {
        metrics["tool_${tool}_last_ms"] = durationMs
        val totalKey = "tool_${tool}_total_ms"
        metrics[totalKey] = (metrics[totalKey] ?: 0) + durationMs
        recordActionResult(tool, success)
    }

    fun getAllMetrics(): Map<String, Long> = metrics.toMap()

    fun reset() {
        metrics.clear()
    }
}
