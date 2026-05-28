package com.unoone.agent.memory

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

        if (allRelevant.isEmpty()) return ""

        return allRelevant.joinToString("; ") { "${it.key}: ${it.value}" }
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