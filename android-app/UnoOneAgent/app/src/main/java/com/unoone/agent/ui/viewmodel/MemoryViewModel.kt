package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.memory.MemoryModule
import com.unoone.agent.storage.entity.MemoryEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MemoryViewModel(memoryModule: MemoryModule) : ViewModel() {

    val memories: StateFlow<List<MemoryEntity>> = memoryModule.allMemories as StateFlow<List<MemoryEntity>>

    private val module = memoryModule

    fun storePreference(key: String, value: String) {
        viewModelScope.launch {
            module.storePreference(key, value)
        }
    }

    fun storeCorrection(original: String, corrected: String) {
        viewModelScope.launch {
            module.storeCorrection(original, corrected)
        }
    }

    fun deleteMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            module.deleteMemory(memory)
        }
    }
}
