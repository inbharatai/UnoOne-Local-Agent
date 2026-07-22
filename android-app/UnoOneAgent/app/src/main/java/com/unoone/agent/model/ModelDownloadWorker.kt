package com.unoone.agent.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.unoone.agent.R
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.modelmanager.ModelInstaller
import com.unoone.agent.modelmanager.ModelManager

/** Process-resilient foreground installer for multi-gigabyte local model artifacts. */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val modelManager = ModelManager(appContext)

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing model id"))
        if (!AgentRuntimeGate.isEnabled()) {
            return Result.failure(workDataOf(KEY_MODEL_ID to modelId, KEY_ERROR to "UnoOne is disabled"))
        }
        val allowMetered = inputData.getBoolean(KEY_ALLOW_METERED, false)
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        if (!allowMetered && connectivity.isActiveNetworkMetered) return Result.retry()
        setForeground(foregroundInfo(modelId, 0, indeterminate = true))

        val result = modelManager.installModel(
            id = modelId,
            onProgress = { _, fileIndex, totalFiles, file, downloaded, total ->
                val percent = if (total > 0L) (downloaded * 100L / total).toInt().coerceIn(0, 100) else 0
                val progress = workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_FILE to file,
                    KEY_FILE_INDEX to fileIndex,
                    KEY_TOTAL_FILES to totalFiles,
                    KEY_PERCENT to percent,
                    KEY_DOWNLOADED to downloaded,
                    KEY_TOTAL to total
                )
                setProgressAsync(progress)
                setForegroundAsync(foregroundInfo(modelId, percent, indeterminate = total <= 0L))
            },
            shouldCancel = { isStopped || !AgentRuntimeGate.isEnabled() }
        )
        return when (result) {
            ModelInstaller.InstallResult.Success -> Result.success(workDataOf(KEY_MODEL_ID to modelId))
            is ModelInstaller.InstallResult.Failure -> {
                if (result.retryable && runAttemptCount < MAX_TRANSIENT_ATTEMPTS && AgentRuntimeGate.isEnabled()) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_MODEL_ID to modelId, KEY_ERROR to result.reason))
                }
            }
        }
    }

    private fun foregroundInfo(modelId: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Offline model downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading UnoOne model")
            .setContentText(modelId)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_FILE = "file"
        const val KEY_FILE_INDEX = "file_index"
        const val KEY_TOTAL_FILES = "total_files"
        const val KEY_PERCENT = "percent"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_ALLOW_METERED = "allow_metered"
        const val UNIQUE_WORK = "unoone-model-download"
        const val TAG = "unoone-model-download"
        const val MODEL_TAG_PREFIX = "unoone-model-id:"
        internal const val MAX_TRANSIENT_ATTEMPTS = 8
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4102
    }
}
