package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.skills.SkillsModule
import com.unoone.agent.storage.entity.SkillEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SkillsViewModel(private val skillsModule: SkillsModule) : ViewModel() {

    val skills: StateFlow<List<SkillEntity>> = skillsModule.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun createSkill(name: String, triggers: List<String>, steps: List<String>) {
        viewModelScope.launch {
            runCatching { module.saveSkill(name, triggers, steps) }
                .onSuccess { _message.value = "Skill created. Every step will use the normal safety checks." }
                .onFailure { _message.value = it.message ?: "Could not create skill." }
        }
    }

    fun toggleSkill(skill: SkillEntity) {
        viewModelScope.launch {
            if (skill.enabled) module.disableSkill(skill) else module.enableSkill(skill)
        }
    }

    fun deleteSkill(skill: SkillEntity) {
        viewModelScope.launch {
            if (isBuiltIn(skill)) {
                _message.value = "Built-in skills can be disabled but not deleted."
            } else {
                module.deleteSkill(skill)
            }
        }
    }

    fun stepsFor(skill: SkillEntity): List<String> = module.getSkillSteps(skill)

    fun isBuiltIn(skill: SkillEntity): Boolean =
        com.unoone.agent.skills.BuiltInSkillCatalog.names.contains(skill.name)

    fun isSuggestion(skill: SkillEntity): Boolean =
        skill.name.startsWith(com.unoone.agent.skills.SkillLearningPolicy.SUGGESTION_PREFIX)

    fun clearMessage() { _message.value = null }

    private val module = skillsModule
}
