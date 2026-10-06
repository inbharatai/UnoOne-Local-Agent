package com.unoone.agent

import com.unoone.agent.core.voice.*
import com.unoone.agent.core.device.*

/** Pure structural adapter. Never parses transcript or consults the execution-time foreground. */
object VoicePurposeAdapter {
    fun goals(purpose: NativeVoicePurpose): List<NativeDeviceGoal> = purpose.steps.map { step ->
        val pkg = step.app.packageName
        when (step.operation) {
            VoiceOperation.OPEN_APP -> NativeDeviceGoal.OpenApp(pkg)
            VoiceOperation.BACK -> NativeDeviceGoal.Back(pkg)
            VoiceOperation.HOME -> NativeDeviceGoal.NeedsUser("Home is not supported by the bounded native executor")
            VoiceOperation.SCROLL_UP -> NativeDeviceGoal.Scroll(pkg, ScrollDirection.BACKWARD)
            VoiceOperation.SCROLL_DOWN -> NativeDeviceGoal.Scroll(pkg, ScrollDirection.FORWARD)
            VoiceOperation.READ_SCREEN -> NativeDeviceGoal.ReadScreen(pkg)
            else -> NativeDeviceGoal.Interact(ReviewedInteraction(
                NativeTargetSelector(requireNotNull(step.exactLabel), matchMode = NativeTargetMatchMode.CASE_INSENSITIVE_UNIQUE),
                when (step.operation) {
                    VoiceOperation.CLICK -> ReviewedOperation.CLICK
                    VoiceOperation.FOCUS -> ReviewedOperation.FOCUS
                    VoiceOperation.SELECT_TAB -> ReviewedOperation.SELECT_TAB
                    VoiceOperation.WRITE -> ReviewedOperation.WRITE
                    else -> error("Unsupported operation")
                }, step.exactValue), pkg)
        }
    }
    fun owlSupported(purpose: NativeVoicePurpose): Boolean = purpose.steps.map { it.app.packageName }.distinct().size == 1 &&
        purpose.steps.withIndex().all { (index, step) -> when (step.operation) {
            VoiceOperation.OPEN_APP -> index == 0
            VoiceOperation.READ_SCREEN, VoiceOperation.CLICK, VoiceOperation.FOCUS, VoiceOperation.SELECT_TAB, VoiceOperation.WRITE -> true
            else -> false
        } }
    fun description(review: VoiceTaskReview): String = buildString {
        append("Use Owl locally for this exact scope: ")
        append(review.purpose.steps.joinToString("; then ") {
            "${it.operation.name} in ${it.app.label} (${it.app.packageName})" +
                (it.exactLabel?.let { label -> ", unique label [$label] (case-insensitive)" } ?: "") +
                (it.exactValue?.let { value -> ", exact value [$value]" } ?: "")
        })
        append(". Allow local screen frames: ${review.allowLocalFrames}. Maximum ${review.maxSteps} steps and ${review.maxSeconds} seconds. Say exactly confirm, or cancel.")
    }
}
