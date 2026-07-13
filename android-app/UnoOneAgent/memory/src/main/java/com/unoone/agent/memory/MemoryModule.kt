package com.unoone.agent.memory

import com.unoone.agent.core.memory.OutcomeMemoryPolicy
import com.unoone.agent.core.memory.OutcomeRecord
import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

class MemoryModule(private val memoryDao: MemoryDao) {

    val allMemories: Flow<List<MemoryEntity>> = memoryDao.getAll()

    suspend fun storePreference(key: String, value: String) {
        Logger.d("Storing preference: $key = $value")
        val existing = memoryDao.getByKey(key)
        if (existing != null) {
            memoryDao.update(existing.copy(value = value, updatedAt = System.currentTimeMillis()))
        } else {
            memoryDao.insert(MemoryEntity(key = key, value = value, type = "preference"))
        }
    }

    suspend fun getPreference(key: String): String? {
        return memoryDao.getByKey(key)?.value
    }

    suspend fun storeCorrection(original: String, corrected: String) {
        Logger.d("Storing correction: $original -> $corrected")
        memoryDao.insert(
            MemoryEntity(
                key = "correction_${original.hashCode()}",
                value = corrected,
                type = "correction"
            )
        )
    }

    /**
     * Records the outcome of a tool call for a given command so the planner can be warned about prior
     * failures and reassured about prior successes for similar future requests. Keyed by
     * `outcome:<signature>:<tool>` and upserted, so the latest outcome per (command-signature, tool)
     * pair is kept. The signature + matching logic lives in [OutcomeMemoryPolicy] (JVM-tested); this
     * method only does Room I/O. Failures here are logged and swallowed — memory must never break a
     * command.
     */
    suspend fun storeOutcome(command: String, tool: String, success: Boolean, errorMessage: String? = null) {
        try {
            val signature = OutcomeMemoryPolicy.signature(command)
            if (signature.isBlank()) return
            val key = "outcome:$signature:$tool"
            val value = (if (success) "ok" else "fail") + "|" + (errorMessage?.take(200) ?: "")
            val now = System.currentTimeMillis()
            val existing = memoryDao.getByKey(key)
            if (existing != null) {
                memoryDao.update(existing.copy(value = value, updatedAt = now))
            } else {
                memoryDao.insert(MemoryEntity(key = key, value = value, type = "outcome", updatedAt = now, createdAt = now))
            }
        } catch (e: Exception) {
            Logger.w("MemoryModule: storeOutcome failed (non-fatal): ${e.message}")
        }
    }

    suspend fun getRelevantContext(query: String): String {
        Logger.d("Getting memory context for: $query")
        val words = query.lowercase().split(Regex("\\s+"))
        val preferences = memoryDao.getByTypeList("preference")
        val corrections = memoryDao.getByTypeList("correction")
        val patterns = memoryDao.getByTypeList("pattern")

        val relevantPreferences = preferences.filter { memory ->
            words.any { memory.key.contains(it, ignoreCase = true) || memory.value.contains(it, ignoreCase = true) }
        }

        val relevantCorrections = corrections.filter { memory ->
            words.any { memory.value.contains(it, ignoreCase = true) }
        }

        val relevantPatterns = patterns.filter { memory ->
            words.any { memory.key.contains(it, ignoreCase = true) }
        }

        val allRelevant = (relevantPreferences + relevantCorrections + relevantPatterns)
            .distinctBy { it.key }

        val memoryContext = if (allRelevant.isEmpty()) "" else
            allRelevant.joinToString("; ") { "${it.key}: ${it.value}" }

        // Outcome-learned hint: surface prior tool outcomes for similar requests.
        val outcomeHint = try {
            val records = memoryDao.getByTypeList("outcome").mapNotNull { it.toOutcomeRecord() }
            OutcomeMemoryPolicy.render(OutcomeMemoryPolicy.relevantOutcomes(query, records))
        } catch (e: Exception) {
            Logger.w("MemoryModule: outcome retrieval failed (non-fatal): ${e.message}"); ""
        }

        return listOf(memoryContext, outcomeHint).filter { it.isNotBlank() }.joinToString("; ")
    }

    /** Parses an `outcome:` memory row back into an [OutcomeRecord]. */
    private fun MemoryEntity.toOutcomeRecord(): OutcomeRecord? {
        if (!key.startsWith("outcome:")) return null
        val rest = key.removePrefix("outcome:")
        val tool = rest.substringAfterLast(":")
        val signature = rest.substringBeforeLast(":")
        if (signature.isBlank() || tool.isBlank()) return null
        val status = value.substringBefore("|")
        val error = value.substringAfter("|", "").ifBlank { null }
        return OutcomeRecord(
            signature = signature,
            tool = tool,
            success = status == "ok",
            errorMessage = error,
            updatedAt = updatedAt
        )
    }

    suspend fun storePattern(trigger: String, action: String) {
        memoryDao.insert(
            MemoryEntity(
                key = "pattern_${trigger.hashCode()}",
                value = action,
                type = "pattern"
            )
        )
    }

    suspend fun deleteMemory(memory: MemoryEntity) {
        memoryDao.delete(memory)
    }
}