package com.unoone.agent.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.brain.BrainSelection
import com.unoone.agent.brain.BrainSelfTest
import com.unoone.agent.brain.BrainSelfTestResult
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.Result
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
 * and refresh. Also drives the **Brain Model** section: lists the selectable brain profiles from
 * [BrainModelRegistry], lets the user pick one (persisted via [BrainSelection]) and load it, and
 * runs an on-device [BrainSelfTest] (load + a read-only tool-call probe) per profile.
 */
class ModelStatusViewModel(
    context: Context,
    modelMetadataDao: ModelMetadataDao? = null,
    private val orchestrator: AgentOrchestrator? = null
) : ViewModel() {

    private val appContext = context.applicationContext
    private val modelManager = ModelManager(appContext, modelMetadataDao)
    private val brainSelfTest = orchestrator?.let { BrainSelfTest(it, modelManager) }

    /** One row per manifest model, merged with on-disk status + health. */
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

    /** One row per selectable brain profile (Gemma 4 E2B / Gemma 3n E4B). */
    data class BrainProfileRow(
        val manifestId: String,
        val displayName: String,
        val modelFamily: String,
        val experimentalLabel: String?,
        val isLegacy: Boolean,
        val isDeviceVerified: Boolean,
        val minimumRamMb: Int,
        val recommendedRamMb: Int,
        val installed: Boolean,
        val isSelected: Boolean,
        val isLoaded: Boolean,
        val backend: String,
        val lastLoadError: String,
        val description: String
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

    private val _brainProfiles = MutableStateFlow<List<BrainProfileRow>>(emptyList())
    val brainProfiles: StateFlow<List<BrainProfileRow>> = _brainProfiles.asStateFlow()

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

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { buildRows() }
            _rows.value = rows
            _brainProfiles.value = withContext(Dispatchers.IO) { buildBrainProfiles() }
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

    /**
     * Persist a brain selection and load it immediately (if installed + the orchestrator is
     * available). The default stays Gemma 3n E4B; selecting Gemma 4 is the user's explicit choice.
     */
    fun selectBrain(manifestId: String) {
        if (_brainBusy.value) return
        val spec = BrainModelRegistry.resolveOrDefault(manifestId)
        BrainSelection.set(appContext, spec.manifestId)
        if (orchestrator == null) {
            _resultMessage.value = "${spec.displayName} selected (loads on next start)."
            refresh()
            return
        }
        _brainBusy.value = true
        _resultMessage.value = null
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { modelManager.getLlmModelPath(spec) }
            if (path == null) {
                _brainBusy.value = false
                _resultMessage.value =
                    "${spec.displayName} selected but not installed. Install it below; it loads on next start."
                refresh()
            } else {
                val res = withContext(Dispatchers.IO) { orchestrator.loadLlmModel(path, spec) }
                _brainBusy.value = false
                _resultMessage.value = if (res is Result.Success) {
                    "${spec.displayName} selected and loaded on ${orchestrator.loadedBrainBackend()}."
                } else {
                    "${spec.displayName} selected but failed to load: ${(res as? Result.Error)?.message}"
                }
                refresh()
            }
        }
    }

    /** Run the on-device self-test for a brain profile (load + a read-only tool-call probe). */
    fun runBrainSelfTest(manifestId: String) {
        val test = brainSelfTest
        if (test == null || _brainBusy.value) return
        val spec = BrainModelRegistry.resolveOrDefault(manifestId)
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

    fun consumeResultMessage() { _resultMessage.value = null }

    private suspend fun buildRows(): List<ModelRow> {
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
                verified = status?.present == true && health.verified,
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

    private fun buildBrainProfiles(): List<BrainProfileRow> {
        val selectedId = BrainSelection.selected(appContext).manifestId
        val loaded = orchestrator?.loadedBrainProfile()
        val loadedBackend = orchestrator?.loadedBrainBackend() ?: ""
        val loadedError = orchestrator?.lastBrainLoadError() ?: ""
        return BrainModelRegistry.all.map { spec ->
            val isThisLoaded = loaded?.manifestId == spec.manifestId
            BrainProfileRow(
                manifestId = spec.manifestId,
                displayName = spec.displayName,
                modelFamily = spec.modelFamily.name,
                experimentalLabel = spec.experimentalLabel,
                isLegacy = spec.isLegacy,
                isDeviceVerified = spec.isDeviceVerified,
                minimumRamMb = spec.minimumRamMb,
                recommendedRamMb = spec.recommendedRamMb,
                installed = modelManager.getLlmModelPath(spec) != null,
                isSelected = spec.manifestId == selectedId,
                isLoaded = isThisLoaded,
                backend = if (isThisLoaded) loadedBackend else "",
                lastLoadError = if (isThisLoaded) loadedError else "",
                description = spec.description
            )
        }
    }
}