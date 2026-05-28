package com.unoone.agent.modelmanager

import android.content.Context
import android.os.Environment
import com.unoone.agent.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class ModelManager(private val context: Context) {

    private val modelBasePath: String
        get() = Environment.getExternalStorageDirectory()
            .resolve("Android/data/${context.packageName}/files/models")
            .absolutePath

    private val appPrivateModelPath: String
        get() = context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.resolve("models").absolutePath

    fun detectModels(): List<ModelStatus> {
        val models = mutableListOf<ModelStatus>()
        val base = File(appPrivateModelPath)

        if (!base.exists()) {
            base.mkdirs()
        }

        val expectedModels = listOf(
            "gemma-local" to "llm",
            "sherpa-asr" to "asr",
            "sherpa-tts" to "tts",
            "vad" to "vad",
            "punctuation" to "punctuation",
            "ocr-optional" to "ocr"
        )

        for ((folderName, type) in expectedModels) {
            val folder = File(base, folderName)
            val present = folder.exists() && folder.isDirectory && folder.listFiles()?.isNotEmpty() == true
            val sizeMb = if (present) folder.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024 * 1024) else 0L
            models.add(
                ModelStatus(
                    name = folderName,
                    type = type,
                    present = present,
                    loaded = false,
                    sizeMb = sizeMb
                )
            )
        }

        Logger.d("Detected ${models.count { it.present }} models present out of ${models.size}")
        return models
    }

    suspend fun verifyChecksum(path: String, expected: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(path)
            if (!file.exists()) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual == expected.lowercase()
        } catch (e: Exception) {
            Logger.e("Checksum verification failed for $path", e)
            false
        }
    }

    fun getStorageUsageMb(): Long {
        val base = File(appPrivateModelPath)
        if (!base.exists()) return 0L
        return base.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / (1024 * 1024)
    }

    fun getModelFolderPath(modelName: String): String {
        return File(appPrivateModelPath, modelName).absolutePath
    }

    fun ensureModelDirectories() {
        val base = File(appPrivateModelPath)
        listOf("gemma-local", "sherpa-asr", "sherpa-tts", "vad", "punctuation", "ocr-optional").forEach {
            File(base, it).mkdirs()
        }
    }

    data class ModelStatus(
        val name: String,
        val type: String,
        val present: Boolean,
        val loaded: Boolean,
        val sizeMb: Long
    )
}
