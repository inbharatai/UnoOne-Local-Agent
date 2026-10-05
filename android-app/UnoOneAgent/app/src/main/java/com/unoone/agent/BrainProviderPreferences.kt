package com.unoone.agent

import android.content.Context
import androidx.core.content.edit
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.BrainRuntime
import com.unoone.agent.modelmanager.ModelManager
import java.io.File

/** Consent is independent of installation, runtime readiness and physical-device qualification. */
class BrainProviderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("unoone_brain_provider", Context.MODE_PRIVATE)
    var qwenOptIn: Boolean
        get() = prefs.getBoolean("qwen_experimental_opt_in", false)
        set(value) { prefs.edit(commit = true) { putBoolean("qwen_experimental_opt_in", value) } }

    // Kept off until capture freshness AND a trustworthy privacy attestation are wired.
    var experimentalVisionOptIn: Boolean
        get() = prefs.getBoolean("experimental_vision_opt_in", false)
        set(value) { prefs.edit(commit = true) { putBoolean("experimental_vision_opt_in", value) } }
}

/** Shared startup/recovery/UI/self-test dispatch. Never pass an MNN config to getLlmModelPath. */
suspend fun ModelManager.resolveBrainLoadPath(spec: BrainModelSpec): String? = when (spec.runtime) {
    BrainRuntime.MNN -> getMnnModelFolder(spec)?.let { folder ->
        File(folder, "config.json").takeIf { it.isFile }?.absolutePath
    }
    BrainRuntime.LITERT_LM -> getLlmModelPath(spec)
}
