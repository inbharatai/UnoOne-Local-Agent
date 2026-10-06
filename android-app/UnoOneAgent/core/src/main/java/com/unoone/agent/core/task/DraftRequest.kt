package com.unoone.agent.core.task

import kotlinx.serialization.json.*

/** Native envelope only. User text is always encoded as prompt, never decoded as authority. */
data class DraftRequest(val prompt: String, val requiredPhrases: List<String> = emptyList()) {
    init {
        require(prompt.isNotBlank() && prompt.length <= 4000)
        require(requiredPhrases.size <= 6 && requiredPhrases.sumOf { it.length } <= 256)
        require(requiredPhrases.all { it.isNotBlank() && it.length <= 80 })
        require(requiredPhrases.distinct().size == requiredPhrases.size) { "Duplicate exact phrases" }
    }
    fun encode(): String = buildJsonObject {
        put("v", 1); put("prompt", prompt)
        put("requiredPhrases", JsonArray(requiredPhrases.map(::JsonPrimitive)))
    }.toString()

    companion object {
        /** Canonical native encoding also rejects duplicate keys, coercions and unknown fields. */
        fun decode(encoded: String): DraftRequest {
            require(encoded.length <= 26000)
            val obj = Json.parseToJsonElement(encoded) as? JsonObject ?: error("Draft object required")
            require(obj.keys == setOf("v", "prompt", "requiredPhrases"))
            require(obj["v"] == JsonPrimitive(1))
            val prompt = obj["prompt"] as? JsonPrimitive ?: error("Prompt required")
            require(prompt.isString)
            val phrases = obj["requiredPhrases"] as? JsonArray ?: error("Phrases required")
            require(phrases.size <= 6)
            val result = DraftRequest(prompt.content, phrases.map {
                val p = it as? JsonPrimitive ?: error("Phrase must be text")
                require(p.isString); p.content
            })
            require(encoded == result.encode()) { "Non-native draft encoding" }
            return result
        }
    }
}
