package com.unoone.agent

import android.content.Context
import androidx.core.content.edit
import com.unoone.agent.core.model.BrainModelSpec
import com.unoone.agent.core.model.BrainRuntime
import com.unoone.agent.modelmanager.ModelManager
import java.io.File
import com.unoone.agent.core.model.BrainExperimentalConsent
import com.unoone.agent.core.model.experimentalConsent

/** Consent is independent of installation, runtime readiness and physical-device qualification. */
class BrainProviderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("unoone_brain_provider", Context.MODE_PRIVATE)
    var qwenOptIn: Boolean
        get() = prefs.getBoolean("qwen_experimental_opt_in", false)
        set(value) { prefs.edit(commit = true) { putBoolean("qwen_experimental_opt_in", value) } }

    var owlOptIn: Boolean
        get() = prefs.getBoolean("gui_owl_experimental_opt_in", false)
        set(value) { prefs.edit(commit = true) { putBoolean("gui_owl_experimental_opt_in", value) } }

    fun hasConsent(spec: BrainModelSpec): Boolean = when (spec.experimentalConsent()) {
        BrainExperimentalConsent.NONE -> true
        BrainExperimentalConsent.QWEN -> qwenOptIn
        BrainExperimentalConsent.GUI_OWL -> owlOptIn
    }

    fun grantConsent(spec: BrainModelSpec) {
        when (spec.experimentalConsent()) {
            BrainExperimentalConsent.NONE -> Unit
            BrainExperimentalConsent.QWEN -> qwenOptIn = true
            BrainExperimentalConsent.GUI_OWL -> owlOptIn = true
        }
    }

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
    BrainRuntime.LLAMA_CPP -> getGgufModelArtifacts(spec)?.root
}

/** Conservative admission only: this estimate is neither measured RSS nor a success guarantee. */
fun Context.owlLoadAdmissionError(spec: BrainModelSpec): String? {
    if (spec.id != com.unoone.agent.core.model.BrainModelId.GUI_OWL_1_5_4B_INSTRUCT) return null
    val info = android.app.ActivityManager.MemoryInfo()
    (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
    if (info.totalMem / (1024 * 1024) < spec.minimumRamMb) return "GUI-Owl requires the conservative 8 GB device RAM policy; phone suitability is not measured."
    if (info.lowMemory || info.availMem < com.unoone.agent.core.model.GuiOwlArtifact.REQUIRED_AVAILABLE_BYTES) {
        return "Insufficient available RAM for GUI-Owl model + projector + 2 GiB KV/vision/runtime policy overhead. No fallback."
    }
    return null
}
