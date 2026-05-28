package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(context: Context) : ViewModel() {

    private val modelManager = ModelManager(context)

    private val _modelStatuses = MutableStateFlow<List<ModelManager.ModelStatus>>(emptyList())
    val modelStatuses: StateFlow<List<ModelManager.ModelStatus>> = _modelStatuses.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _modelStatuses.value = modelManager.detectModels()
            _storageUsageMb.value = modelManager.getStorageUsageMb()
        }
    }

    fun ensureModelDirectories() {
        modelManager.ensureModelDirectories()
    }
}
