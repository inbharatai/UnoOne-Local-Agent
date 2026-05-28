package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.storage.dao.ActionLogDao
import com.unoone.agent.storage.entity.ActionLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LogsViewModel(private val actionLogDao: ActionLogDao) : ViewModel() {

    private val _logs = MutableStateFlow<List<ActionLogEntity>>(emptyList())
    val logs: StateFlow<List<ActionLogEntity>> = _logs.asStateFlow()

    init {
        viewModelScope.launch {
            actionLogDao.getRecent(100).collect { list ->
                _logs.value = list
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            actionLogDao.clearAll()
        }
    }
}
