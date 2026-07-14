package com.unoone.agent.localbrain

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.modelmanager.ModelManager
import org.junit.Test

/**
 * One-shot diagnostic: prints exactly what ModelManager.getLlmModelPath() sees so we can tell whether
 * a null result is a path, ownership/SELinux, or manifest-spec problem. Runs as the real untrusted_app
 * instrumentation context (not run-as), so it reflects what the brain tests actually see.
 */
class ModelPathDiagnosticTest {

    @Test
    fun dumpModelPathResolution() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val mm = ModelManager(context)
        mm.ensureModelDirectories()
        val extRoot = context.getExternalFilesDir("models")
        val tag = "UnoOneDiag"
        Log.i(tag, "getExternalFilesDir(models)=${extRoot?.absolutePath}")
        Log.i(tag, "filesDir=${context.filesDir.absolutePath}")
        val folder = java.io.File(extRoot, "brain/gemma-4-e2b")
        Log.i(tag, "brain dir path=${folder.absolutePath}")
        Log.i(tag, "brain dir exists=${folder.exists()} isDirectory=${folder.isDirectory}")
        val listed = folder.listFiles()
        Log.i(tag, "listFiles()=${listed?.size ?: "null (denied/missing)"}")
        listed?.forEach { Log.i(tag, "  entry: ${it.name} len=${it.length()} isFile=${it.isFile}") }
        val path = mm.getLlmModelPath()
        Log.i(tag, "getLlmModelPath()=$path")
        // Also try the exact expected file
        val exact = java.io.File(folder, "gemma-4-E2B-it.litertlm")
        Log.i(tag, "exact file exists=${exact.exists()} len=${exact.length()} canRead=${exact.canRead()}")
    }
}