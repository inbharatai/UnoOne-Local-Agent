package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.brain.BrainSelfTest
import com.unoone.agent.brain.BrainSelfTestResult
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.Result
import com.unoone.agent.modelmanager.ModelInstaller
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.storage.dao.ModelMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives model installation, health and the sole Gemma 4 E2B brain card. */
class ModelStatusViewModel(
    context: Context,
    modelMetadataDao: ModelMetadataDao? = null,
    private val orchestrator: AgentOrchestrator? = null
) : ViewModel() {

    private val appContext = context.applicationContext
    private val modelManager = ModelManager(appContext, modelMetadataDao)
    private val brainSelfTest = orchestrator?.let { BrainSelfTest(it, modelManager) }

    data class ModelRow(
        val id: String,
        val folder: String,
        val type: String,
        val version: String,
        val present: Boolean,
        val healthy: Boolean,
        val verified: Boolean,
        val sizeMb: Long,
        val backend: String,
        val minRamMb: Int,
        val language: String,
        val sha256Preview: String,
        val healthMessage: String
    )

    data class BrainStatusRow(
        val manifestId: String,
        val displayName: String,
        val isDeviceVerified: Boolean,
        val minimumRamMb: Int,
        val recommendedRamMb: Int,
        val installed: Boolean,
        val isLoaded: Boolean,
        val backend: String,
        val lastLoadError: String,
        val description: String
    )

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

    private val _brainStatus = MutableStateFlow<BrainStatusRow?>(null)
    val brainStatus: StateFlow<BrainStatusRow?> = _brainStatus.asStateFlow()

    private val _selfTest = MutableStateFlow<BrainSelfTestResult?>(null)
    val selfTest: StateFlow<BrainSelfTestResult?> = _selfTest.asStateFlow()

    private val _brainBusy = MutableStateFlow(false)
    val brainBusy: StateFlow<Boolean> = _brainBusy.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _rows.value = withContext(Dispatchers.IO) { buildRows() }
            _brainStatus.value = withContext(Dispatchers.IO) { buildBrainStatus() }
            _storageUsageMb.value = withContext(Dispatchers.IO) { modelManager.getStorageUsageMb() }
        }
    }

    fun installModel(id: String) {
        if (_busy.value) return
        _busy.value = true
        _resultMessage.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                modelManager.installModel(id) { modelId, fileIndex, totalFiles, file, downloaded, total ->
                    val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
                    _progress.value = InstallProgress(
                        modelId = modelId,
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

    fun loadBrain() {
        if (_brainBusy.value) return
        val spec = BrainModelRegistry.GEMMA_4_E2B
        if (orchestrator == null) {
            _resultMessage.value = "${spec.displayName} will load automatically when the app starts and the artifact is healthy."
            return
        }
        _brainBusy.value = true
        _resultMessage.value = null
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { modelManager.getLlmModelPath(spec) }
            if (path == null) {
                _brainBusy.value = false
                _resultMessage.value = "${spec.displayName} is not installed. Add the integrity-verified artifact first."
                refresh()
                return@launch
            }
            val result = withContext(Dispatchers.IO) { orchestrator.loadLlmModel(path, spec) }
            _brainBusy.value = false
            _resultMessage.value = if (result is Result.Success) {
                "${spec.displayName} loaded on ${orchestrator.loadedBrainBackend()}."
            } else {
                "${spec.displayName} failed to load: ${(result as? Result.Error)?.message}"
            }
            refresh()
        }
    }

    fun runBrainSelfTest() {
        val test = brainSelfTest
        if (test == null || _brainBusy.value) return
        val spec = BrainModelRegistry.GEMMA_4_E2B
        _brainBusy.value = true
        _resultMessage.value = null
        _selfTest.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { test.run(spec) }
            _selfTest.value = result
            _brainBusy.value = false
            _resultMessage.value = result.message
            refresh()
        }
    }

    fun consumeResultMessage() {
        _resultMessage.value = null
    }

    private suspend fun buildRows(): List<ModelRow> {
        val manifest = modelManager.loadManifest()
        val statuses = modelManager.detectModels().associateBy { it.name }
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
                verified = status?.present == true && health.verified,
                sizeMb = status?.sizeMb ?: 0L,
                backend = descriptor.backend.name,
                minRamMb = descriptor.minRamMb,
                language = descriptor.defaultLanguage,
                sha256Preview = descriptor.files.firstOrNull { it.sha256.isNotBlank() }
                    ?.sha256?.take(12)?.let { "$it…" } ?: "—",
                healthMessage = health.message
            )
        }
    }

    private fun buildBrainStatus(): BrainStatusRow {
        val spec = BrainModelRegistry.GEMMA_4_E2B
        val loaded = orchestrator?.loadedBrainProfile()
        val isLoaded = loaded?.manifestId == spec.manifestId
        return BrainStatusRow(
            manifestId = spec.manifestId,
            displayName = spec.displayName,
            isDeviceVerified = spec.isDeviceVerified,
            minimumRamMb = spec.minimumRamMb,
            recommendedRamMb = spec.recommendedRamMb,
            installed = modelManager.getLlmModelPath(spec) != null,
            isLoaded = isLoaded,
            backend = if (isLoaded) orchestrator?.loadedBrainBackend().orEmpty() else "",
            lastLoadError = orchestrator?.lastBrainLoadError().orEmpty(),
            description = spec.description
        )
    }
}
