package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.skills.SkillsModule
import com.unoone.agent.storage.entity.SkillEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SkillsViewModel(private val skillsModule: SkillsModule) : ViewModel() {

    val skills: StateFlow<List<SkillEntity>> = skillsModule.allSkills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createSkill(name: String, triggers: List<String>, steps: List<String>) {
        viewModelScope.launch {
            module.saveSkill(name, triggers, steps)
        }
    }

    fun toggleSkill(skill: SkillEntity) {
        viewModelScope.launch {
            if (skill.enabled) module.disableSkill(skill) else module.enableSkill(skill)
        }
    }

    fun deleteSkill(skill: SkillEntity) {
        viewModelScope.launch {
            module.deleteSkill(skill)
        }
    }

    private val module = skillsModule
}