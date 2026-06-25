package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.core.util.Logger
import com.unoone.agent.modelmanager.ModelInstaller
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.storage.dao.ModelMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Model Status / Settings screen. Lists every model declared in the bundled
 * [models_manifest.json] merged with its on-disk state ([ModelManager.detectModels]) and its
 * verified health ([ModelManager.modelHealth]). Supports install (streaming progress), uninstall,
 * and refresh. The brain (LLM) is *not* loaded here — the app loads it on startup; this screen only
 * reports file presence/health so the user can repair a corrupt or missing model.
 */
class ModelStatusViewModel(
    context: Context,
    modelMetadataDao: ModelMetadataDao? = null
) : ViewModel() {

    private val modelManager = ModelManager(context.applicationContext, modelMetadataDao)

    /** One row per manifest model, merged with on-disk status + health. */
    data class ModelRow(
        val id: String,
        val folder: String,
        val type: String,
        val version: String,
        val present: Boolean,
        val healthy: Boolean,
        val sizeMb: Long,
        val backend: String,
        val minRamMb: Int,
        val language: String,
        val sha256Preview: String,
        val healthMessage: String
    )

    /** Live install progress for the model currently being downloaded (if any). */
    data class InstallProgress(
        val modelId: String,
        val file: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val percent: Int,
        val active: Boolean,
        val message: String
    )

    private val _rows = MutableStateFlow<List<ModelRow>>(emptyList())
    val rows: StateFlow<List<ModelRow>> = _rows.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { buildRows() }
            _rows.value = rows
            _storageUsageMb.value = withContext(Dispatchers.IO) { modelManager.getStorageUsageMb() }
        }
    }

    fun installModel(id: String) {
        if (_busy.value) return
        _busy.value = true
        _resultMessage.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                modelManager.installModel(id) { mid, fileIndex, totalFiles, file, downloaded, total ->
                    val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
                    _progress.value = InstallProgress(
                        modelId = mid,
                        file = file,
                        fileIndex = fileIndex,
                        totalFiles = totalFiles,
                        percent = percent,
                        active = true,
                        message = "Downloading $file ($percent%) — file ${fileIndex + 1}/$totalFiles"
                    )
                }
            }
            _progress.value = null
            _busy.value = false
            _resultMessage.value = when (result) {
                is ModelInstaller.InstallResult.Success -> {
                    refresh()
                    "Installed: $id"
                }
                is ModelInstaller.InstallResult.Failure -> "Install failed: ${result.reason}"
            }
        }
    }

    fun uninstallModel(id: String) {
        if (_busy.value) return
        _busy.value = true
        _resultMessage.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { modelManager.uninstallModel(id) }
            _busy.value = false
            _resultMessage.value = "Uninstalled: $id"
            refresh()
        }
    }

    fun consumeResultMessage() { _resultMessage.value = null }

    private fun buildRows(): List<ModelRow> {
        val manifest = modelManager.loadManifest()
        val statuses = modelManager.detectModels().associateBy { it.name } // keyed by folder
        return manifest.models.map { descriptor ->
            val status = statuses[descriptor.folder]
            val health = modelManager.modelHealth(descriptor.id)
            ModelRow(
                id = descriptor.id,
                folder = descriptor.folder,
                type = descriptor.type.name,
                version = descriptor.version,
                present = status?.present == true,
                healthy = status?.present == true && health.healthy,
                sizeMb = status?.sizeMb ?: 0L,
                backend = descriptor.backend.name,
                minRamMb = descriptor.minRamMb,
                language = descriptor.defaultLanguage,
                sha256Preview = descriptor.files.firstOrNull { it.sha256.isNotBlank() }?.sha256?.take(12)
                    ?.let { "$it…" } ?: "—",
                healthMessage = health.message
            )
        }
    }
}