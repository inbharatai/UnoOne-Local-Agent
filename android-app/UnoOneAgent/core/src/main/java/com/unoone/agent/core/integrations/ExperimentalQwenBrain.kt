package com.unoone.agent.core.integrations

import com.unoone.agent.core.device.*

data class ExperimentalModelDescriptor(val id: String, val source: String, val revision: String,
    val availability: IntegrationAvailability)

/** A selectable descriptor/seam only, NOT an inference engine or functional planner. */
class ExperimentalQwenBrain : UnoBrain {
    override val capabilities = BrainCapabilities(chat = false, devicePlanning = false, vision = false)
    val descriptor = ExperimentalModelDescriptor("gui-owl-1.5-2b-instruct-experimental",
        "https://huggingface.co/mPLUG/GUI-Owl-1.5-2B-Instruct", "528ceaec795bbfbe6103bd79e03db849feadfb24",
        IntegrationAvailability(false, "Experimental Qwen3-VL derivative: no qualified Android inference runtime, artifact or device validation"))
    private fun unavailable(): Nothing = throw UnsupportedOperationException(descriptor.availability.reason)
    override suspend fun plan(request: DevicePlanRequest): DeviceAction = unavailable()
    override suspend fun chat(message: String): String = unavailable()
    override suspend fun interpretScreen(state: PerceptionState): ScreenInterpretation = unavailable()
    override suspend fun groundTarget(description: String, state: PerceptionState): TargetGrounding = unavailable()
    override suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState): OutcomeAdvice = unavailable()
}
