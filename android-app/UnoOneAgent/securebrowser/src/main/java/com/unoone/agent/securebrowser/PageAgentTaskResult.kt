package com.unoone.agent.securebrowser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PageAgentTaskResult(
    val success: Boolean,
    val data: String = "",
    val taskId: Long? = null
)

/** Decodes WebView evaluateJavascript output without guessing from words in the result text. */
object PageAgentTaskResultDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): Result<PageAgentTaskResult> = runCatching {
        require(!raw.isNullOrBlank() && raw != "null") { "No result returned" }
        val first = json.parseToJsonElement(raw)
        val objectJson = if (first is JsonPrimitive && first.isString) first.content else raw
        json.decodeFromString<PageAgentTaskResult>(objectJson)
    }
}

/** Allows one browser task at a time and prevents timeout/cancel/stale JS callbacks racing. */
class PageAgentTaskGate {
    private val sequence = java.util.concurrent.atomic.AtomicLong(0)
    private val active = java.util.concurrent.atomic.AtomicLong(0)

    fun begin(): Long? {
        val id = sequence.incrementAndGet()
        return if (active.compareAndSet(0, id)) id else null
    }

    fun tryComplete(id: Long): Boolean = active.compareAndSet(id, 0)

    fun activeId(): Long? = active.get().takeIf { it != 0L }

    fun cancelActive(): Long? {
        while (true) {
            val id = active.get()
            if (id == 0L) return null
            if (active.compareAndSet(id, 0)) return id
        }
    }
}
