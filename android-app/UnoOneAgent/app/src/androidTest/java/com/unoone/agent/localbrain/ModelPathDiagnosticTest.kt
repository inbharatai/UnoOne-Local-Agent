package com.unoone.agent.localbrain

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * One-shot diagnostic for the exact E4B model path.
 *
 * It runs in the real instrumentation context and prints filesystem visibility plus the strict
 * ModelManager result. A present file can still resolve to null when its filename, exact size or
 * SHA-256 does not match the active model contract.
 */
class ModelPathDiagnosticTest {

    @Test
    fun dumpModelPathResolution() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val manager = ModelManager(context)
            manager.ensureModelDirectories()
            val spec = BrainModelRegistry.GEMMA_4_E4B
            val externalRoot = context.getExternalFilesDir("models")
            val modelRoot = externalRoot ?: context.filesDir.resolve("models")
            val tag = "UnoOneDiag"

        Log.i(tag, "getExternalFilesDir(models)=${externalRoot?.absolutePath}")
        Log.i(tag, "filesDir=${context.filesDir.absolutePath}")

        val folder = java.io.File(modelRoot, spec.modelFolder)
        Log.i(tag, "brain dir path=${folder.absolutePath}")
        Log.i(tag, "brain dir exists=${folder.exists()} isDirectory=${folder.isDirectory}")
        val listed = folder.listFiles()
        Log.i(tag, "listFiles()=${listed?.size ?: "null (denied/missing)"}")
        listed?.forEach { file ->
            Log.i(tag, "entry=${file.name} len=${file.length()} isFile=${file.isFile}")
        }

        val exact = java.io.File(folder, spec.fileName)
        Log.i(
            tag,
            "exact file exists=${exact.exists()} len=${exact.length()} canRead=${exact.canRead()}"
        )
            Log.i(tag, "getLlmModelPath()=${manager.getLlmModelPath(spec)}")
        }
    }
}
