package com.unoone.agent.core.model

/** Immutable third-party artifact identity; not an independently reproduced conversion. */
object GuiOwlArtifact {
    const val MANIFEST_ID = "gui-owl-1.5-4b-instruct-gguf"
    const val FOLDER = "brain/gui-owl-1.5-4b-instruct-gguf"
    const val REPOSITORY = "mradermacher/GUI-Owl-1.5-4B-Instruct-GGUF"
    const val REVISION = "9a79d301329062eb02e46f7ccad82c99075bfaaa"
    const val PRIMARY_REFERENCE_REVISION = "3f061c2c562cc860c42bf32542a70e07a7ff4840"
    const val DECODER = "GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf"
    const val PROJECTOR = "GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf"
    const val DECODER_BYTES = 2497282208L
    const val PROJECTOR_BYTES = 453974336L
    const val DECODER_SHA256 = "8e1793b69bb4064671ab6529b43f5b943850f73a9244ad139c16e93a44709232"
    const val PROJECTOR_SHA256 = "b705d940e9b7f212235a16c9c4b8cc9dd9053a1ccf549b99fc069a0ae694b073"
    const val PROVENANCE_DISCLOSURE = "Third-party quantization; canonical source-conversion commit unknown. Model card: MIT; embedded GGUF metadata: Apache-2.0. Retain both notices; no commercial clearance claimed."
    // Admission estimate, NOT a measured peak or an assurance that loading will succeed.
    const val KV_VISION_RUNTIME_OVERHEAD_BYTES = 2147483648L
    const val REQUIRED_AVAILABLE_BYTES = DECODER_BYTES + PROJECTOR_BYTES + KV_VISION_RUNTIME_OVERHEAD_BYTES
    fun url(name: String) = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$name"
}

/** Consent is model identity specific, never inferred from a shared runtime. */
enum class BrainExperimentalConsent { NONE, QWEN, GUI_OWL }
fun BrainModelSpec.experimentalConsent(): BrainExperimentalConsent = when (id) {
    BrainModelId.QWEN3_5_2B -> BrainExperimentalConsent.QWEN
    BrainModelId.GUI_OWL_1_5_4B_INSTRUCT -> BrainExperimentalConsent.GUI_OWL
    else -> BrainExperimentalConsent.NONE
}
