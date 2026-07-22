package com.unoone.agent.ui.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.brain.BrainSelfTest
import com.unoone.agent.brain.BrainSelfTestResult
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.Result
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.model.ModelDownloadWorker
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.storage.dao.ModelMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives model installation, health and the sole Gemma 4 E4B brain card. */
class ModelStatusViewModel(
    context: Context,
    modelMetadataDao: ModelMetadataDao? = null,
    private val orchestrator: AgentOrchestrator? = null
) : ViewModel() {

    private val appContext = context.applicationContext
    private val modelManager = ModelManager(appContext, modelMetadataDao)
    private val brainSelfTest = orchestrator?.let { BrainSelfTest(it, modelManager) }
    private val workManager = WorkManager.getInstance(appContext)

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

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _storageUsageMb = MutableStateFlow(0L)
    val storageUsageMb: StateFlow<Long> = _storageUsageMb.asStateFlow()

    private val _resultMessage = MutableStateFlow<String?>(null)
    val resultMessage: StateFlow<String?> = _resultMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingMeteredInstall = MutableStateFlow<String?>(null)
    val pendingMeteredInstall: StateFlow<String?> = _pendingMeteredInstall.asStateFlow()

    private val downloadObserver = Observer<List<WorkInfo>> { infos ->
        val active = infos.lastOrNull { !it.state.isFinished }
        val latest = active ?: infos.lastOrNull()
        _busy.value = active != null
        if (latest == null) return@Observer
        val modelId = latest.progress.getString(ModelDownloadWorker.KEY_MODEL_ID)
            ?: latest.outputData.getString(ModelDownloadWorker.KEY_MODEL_ID)
            ?: latest.tags.firstOrNull { it.startsWith(ModelDownloadWorker.MODEL_TAG_PREFIX) }
                ?.removePrefix(ModelDownloadWorker.MODEL_TAG_PREFIX)
            ?: "model"
        when (latest.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                _progress.value = InstallProgress(modelId, "", 0, 1, 0, true, "Waiting for allowed network…")
            }
            WorkInfo.State.RUNNING -> {
                val percent = latest.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
                val file = latest.progress.getString(ModelDownloadWorker.KEY_FILE).orEmpty()
                val fileIndex = latest.progress.getInt(ModelDownloadWorker.KEY_FILE_INDEX, 0)
                val totalFiles = latest.progress.getInt(ModelDownloadWorker.KEY_TOTAL_FILES, 1)
                _progress.value = InstallProgress(
                    modelId, file, fileIndex, totalFiles, percent, true,
                    "Downloading $file ($percent%) — file ${fileIndex + 1}/$totalFiles"
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                _progress.value = null
                _resultMessage.value = "Installed: $modelId"
                refresh()
            }
            WorkInfo.State.FAILED -> {
                _progress.value = null
                _resultMessage.value = "Install failed: ${latest.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "unknown error"}"
            }
            WorkInfo.State.CANCELLED -> {
                _progress.value = null
                _resultMessage.value = "Download cancelled; partial data kept for resume."
            }
        }
    }

    init {
        workManager.getWorkInfosByTagLiveData(ModelDownloadWorker.TAG).observeForever(downloadObserver)
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
        if (!AgentRuntimeGate.isEnabled()) {
            _resultMessage.value = "UnoOne is disabled. Enable it before downloading a model."
            return
        }
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivity.isActiveNetworkMetered) {
            _pendingMeteredInstall.value = id
            return
        }
        enqueueModelInstall(id, allowMetered = false)
    }

    fun confirmMeteredInstall() {
        val id = _pendingMeteredInstall.value ?: return
        _pendingMeteredInstall.value = null
        enqueueModelInstall(id, allowMetered = true)
    }

    fun dismissMeteredInstall() {
        _pendingMeteredInstall.value = null
        _resultMessage.value = "Download not started. Connect to Wi-Fi and try again."
    }

    fun cancelInstall() {
        workManager.cancelUniqueWork(ModelDownloadWorker.UNIQUE_WORK)
    }

    private fun enqueueModelInstall(id: String, allowMetered: Boolean) {
        _resultMessage.value = null
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_ID to id,
                ModelDownloadWorker.KEY_ALLOW_METERED to allowMetered
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorker.TAG)
            .addTag("${ModelDownloadWorker.MODEL_TAG_PREFIX}$id")
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
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
        val spec = BrainModelRegistry.GEMMA_4_E4B
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
        val spec = BrainModelRegistry.GEMMA_4_E4B
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

    fun verifyBrainArtifact() {
        if (_brainBusy.value || _verifying.value) return
        _verifying.value = true
        _resultMessage.value = "Verifying the complete E4B file…"
        viewModelScope.launch {
            val health = withContext(Dispatchers.IO) { modelManager.verifyLlmArtifact() }
            _verifying.value = false
            _resultMessage.value = if (health.verified) {
                "Gemma 4 E4B size and SHA-256 are verified."
            } else {
                "E4B verification failed: ${health.message}"
            }
            refresh()
        }
    }

    fun consumeResultMessage() {
        _resultMessage.value = null
    }

    override fun onCleared() {
        workManager.getWorkInfosByTagLiveData(ModelDownloadWorker.TAG).removeObserver(downloadObserver)
        super.onCleared()
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

    private suspend fun buildBrainStatus(): BrainStatusRow {
        val spec = BrainModelRegistry.GEMMA_4_E4B
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
