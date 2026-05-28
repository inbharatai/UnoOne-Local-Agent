package com.unoone.agent.skills

import com.unoone.agent.core.util.Logger
import com.unoone.agent.storage.dao.SkillDao
import com.unoone.agent.storage.entity.SkillEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class SkillsModule(private val skillDao: SkillDao) {

    private val json = Json { ignoreUnknownKeys = true }

    val allSkills: Flow<List<SkillEntity>> = skillDao.getAll()
    val enabledSkills: Flow<List<SkillEntity>> = skillDao.getEnabled()

    suspend fun saveSkill(name: String, triggerPhrases: List<String>, steps: List<String>, riskLevel: Int = 0) {
        Logger.d("Expert: Saving new skill '$name'")
        skillDao.insert(
            SkillEntity(
                name = name,
                triggerPhrases = triggerPhrases.joinToString(","),
                stepsJson = json.encodeToString(ListSerializer(serializer<String>()), steps),
                riskLevel = riskLevel
            )
        )
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
        val lowInput = text.lowercase()
        val skills = enabledSkills.first()
        return skills.find { skill ->
            skill.triggerPhrases.split(",").any { trigger ->
                lowInput.contains(trigger.trim().lowercase())
            }
        }
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