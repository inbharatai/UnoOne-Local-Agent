package com.unoone.agent.skills

import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.MemoryDao
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.MemoryEntity
import com.unoone.agent.storage.entity.SkillEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class SkillsModule(
    private val skillDao: SkillDao,
    private val memoryDao: MemoryDao
) {

    private val json = Json { ignoreUnknownKeys = true }

    val allSkills: Flow<List<SkillEntity>> = skillDao.getAll()
    val enabledSkills: Flow<List<SkillEntity>> = skillDao.getEnabled()

    suspend fun saveSkill(
        name: String,
        triggerPhrases: List<String>,
        steps: List<String>,
        riskLevel: Int = 0,
        enabled: Boolean = true
    ) {
        val cleanName = name.trim()
        val cleanTriggers = triggerPhrases.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotBlank() }
        require(cleanName.isNotBlank() && cleanName.length <= 80) { "Skill name must be 1–80 characters" }
        require(cleanTriggers.isNotEmpty() && cleanTriggers.size <= 8) { "A skill needs 1–8 trigger phrases" }
        require(cleanSteps.isNotEmpty() && cleanSteps.size <= 12) { "A skill needs 1–12 executable steps" }
        require(cleanTriggers.all { it.length <= 120 }) { "A trigger phrase is too long" }
        require(cleanSteps.all { it.length <= 500 }) { "A skill step is too long" }

        Logger.d("Expert: Saving new skill '$name'")
        skillDao.insert(
            SkillEntity(
                name = cleanName,
                triggerPhrases = cleanTriggers.joinToString(","),
                stepsJson = json.encodeToString(ListSerializer(serializer<String>()), cleanSteps),
                riskLevel = riskLevel.coerceIn(0, 3),
                enabled = enabled
            )
        )
    }

    /** Idempotently installs a minimal set of immediately useful, fully safety-routed routines. */
    suspend fun ensureBuiltIns() {
        BuiltInSkillCatalog.definitions.forEach { definition ->
            if (skillDao.getAll().first().none { it.name == definition.name }) {
                runCatching {
                    saveSkill(
                        name = definition.name,
                        triggerPhrases = definition.triggers,
                        steps = definition.steps,
                        riskLevel = definition.riskLevel,
                        enabled = true
                    )
                }.onFailure { Logger.w("Skills: could not seed '${definition.name}': ${it.message}") }
            }
        }
    }

    /**
     * Persistently counts successful safe routines and creates a disabled suggestion after the
     * third use. Suggestions require a visible user enable action before trigger matching sees them.
     */
    suspend fun recordSuccessfulUse(command: String, tool: String): SkillEntity? {
        val suggestion = SkillLearningPolicy.suggestionFor(command, tool) ?: return null
        val key = SkillLearningPolicy.usageKey(tool, suggestion.name)
        val existingUsage = memoryDao.getByKey(key)
        val nextCount = (existingUsage?.value?.toIntOrNull() ?: 0) + 1
        val now = System.currentTimeMillis()
        if (existingUsage == null) {
            memoryDao.insert(MemoryEntity(key = key, value = nextCount.toString(), type = "skill_usage"))
        } else {
            memoryDao.update(existingUsage.copy(value = nextCount.toString(), updatedAt = now))
        }
        if (!SkillLearningPolicy.shouldSuggest(nextCount)) return null

        val existingSkills = skillDao.getAll().first()
        val duplicate = existingSkills.any { getSkillSteps(it) == suggestion.steps }
        if (duplicate) return null
        saveSkill(
            name = suggestion.name,
            triggerPhrases = suggestion.triggers,
            steps = suggestion.steps,
            riskLevel = suggestion.riskLevel,
            enabled = false
        )
        Logger.i("Skills: created disabled learned suggestion '${suggestion.name}'")
        return skillDao.getAll().first().firstOrNull { it.name == suggestion.name }
    }

    suspend fun updateSkill(skill: SkillEntity) {
        skillDao.update(skill)
    }

    suspend fun disableSkill(skill: SkillEntity) {
        skillDao.update(skill.copy(enabled = false))
    }

    suspend fun enableSkill(skill: SkillEntity) {
        skillDao.update(skill.copy(enabled = true))
    }

    suspend fun deleteSkill(skill: SkillEntity) {
        Logger.d("Deleting skill: ${skill.name}")
        skillDao.delete(skill)
    }

    suspend fun findSkillByTrigger(text: String): SkillEntity? {
        return SkillTriggerMatcher.bestMatch(text, enabledSkills.first())
    }

    fun getSkillSteps(skill: SkillEntity): List<String> {
        return try {
            json.decodeFromString(ListSerializer(serializer<String>()), skill.stepsJson)
        } catch (_: Exception) {
            // Fallback for legacy data stored with the old format
            skill.stepsJson.split("\",\"").map { it.replace("\"", "") }
        }
    }
}
