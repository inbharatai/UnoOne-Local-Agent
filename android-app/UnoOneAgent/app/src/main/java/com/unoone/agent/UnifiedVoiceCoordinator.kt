package com.unoone.agent

import android.content.Context
import android.os.SystemClock
import com.unoone.agent.core.voice.*
import com.unoone.agent.core.latency.*
import com.unoone.agent.core.runtime.*
import com.unoone.agent.core.task.*
import com.unoone.agent.voice.VoiceLatency
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Intake and review only. Every action uses the existing NativeTaskRuntime coordinator. */
class UnifiedVoiceCoordinator(private val context: Context, private val orchestrator: AgentOrchestrator,
    private val appResolver: VoiceAppResolver = VoiceAppResolver { name ->
        when (val found = com.unoone.agent.phonecontrol.AppRegistry(context).resolve(name)) {
            is com.unoone.agent.phonecontrol.AppRegistry.Resolution.Found -> AppResolution.Resolved(AppChoice(found.app.packageName, name))
            else -> AppResolution.Unavailable
        }
    }
) {
    private val gate = VoiceReviewGate()
    private val reviewState = MutableStateFlow<VoiceTaskReview?>(null)
    val review = reviewState.asStateFlow()
    private val statusState = MutableStateFlow("")
    val status = statusState.asStateFlow()
    private val stopRegistration = GlobalTaskCancellation.register(this) { it.clear() }
    private val reviewLock = Any()
    private val reviewTraces = mutableMapOf<String, LatencyToken?>()
    private val reviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun clear() = clearWithOutcome(LatencyOutcome.CANCELLED)
    private fun clearWithOutcome(outcome: LatencyOutcome) = synchronized(reviewLock) {
        reviewTraces.values.forEach { VoiceLatency.recorder.close(it, outcome) }
        reviewTraces.clear(); gate.clear(); reviewState.value = null
    }
    fun ownsTrace(trace: LatencyToken?): Boolean = synchronized(reviewLock) {
        trace != null && reviewTraces.values.any { it == trace }
    }
    private fun environment() = VoiceRouteEnvironment(AgentRuntimeGate.isEnabled(), GlobalTaskCancellation.generation, SystemClock.elapsedRealtime())

    fun capture(floating: Boolean = false): VoiceIngress {
        val generation = GlobalTaskCancellation.generation
        val source = if (floating) com.unoone.agent.overlay.FloatingContextEvidence.captureForNewChat()
        else com.unoone.agent.overlay.FloatingContextEvidence.capture()
        val evidence = source?.takeIf { it.isFresh(SystemClock.elapsedRealtime(), generation) }
            ?.let { UnderlyingAppEvidence(it.packageName, it.windowId, it.capturedAtElapsedMs) }
        val ingress = VoiceIngress(UUID.randomUUID().toString(), "", generation, SystemClock.elapsedRealtime(), evidence,
            gate.pendingReview()?.reviewId ?: orchestrator.pendingVoiceReviewId())
        val trace = VoiceLatency.recorder.begin(if (floating) LatencyOrigin.FLOATING else LatencyOrigin.FOREGROUND)
        VoiceLatency.recorder.bindRequest(ingress.requestId, trace)
        VoiceLatency.recorder.mark(trace, LatencyStage.MIC_REQUEST)
        return ingress
    }

    suspend fun accept(ingress: VoiceIngress, trace: LatencyToken? = VoiceLatency.recorder.forRequest(ingress.requestId)) = withContext(CurrentLatencyContext(trace)) {
        VoiceLatency.recorder.acceptOwnership(trace)
        try { acceptOwned(ingress, trace) }
        catch (cancel: CancellationException) { VoiceLatency.recorder.close(trace, LatencyOutcome.CANCELLED); throw cancel }
        catch (error: Exception) { VoiceLatency.recorder.close(trace, LatencyOutcome.FAILED); throw error }
        finally { if (!ownsTrace(trace)) VoiceLatency.recorder.close(trace, LatencyOutcome.COMPLETED_NON_ACTION) }
    }
    private suspend fun acceptOwned(ingress: VoiceIngress, trace: LatencyToken?) {
        // Stop is intentionally ahead of every stale check, including approval resolution.
        if (com.unoone.agent.voice.VoiceControlPolicy.isStop(ingress.transcript)) {
            clear(); orchestrator.cancelCurrentCommand(); return
        }
        if (!AgentRuntimeGate.isEnabled() || ingress.captureGlobalGeneration != GlobalTaskCancellation.generation) { VoiceLatency.recorder.close(trace, LatencyOutcome.REJECTED); return }
        if (orchestrator.resolvePendingVoiceConfirmation(ingress)) return
        if (VoiceReviewGate.speechDecision(ingress.transcript) == null && !ingress.transcript.trim().equals("yes", true))
            orchestrator.invalidateLegacyVoiceReview()
        if (RetainedVoiceRules.requiresDraftClarification(ingress.transcript)) {
            clear(); say("I can prepare a draft, not send it. Say draft WhatsApp or draft email with the exact recipient and message; review and send yourself."); return
        }
        if (!ingress.transcript.trim().startsWith("use owl", true) && orchestrator.isDeterministicVoiceRule(ingress.transcript)) {
            clear(); await(orchestrator.taskRuntime.submitPreparedCommand(ingress.transcript, com.unoone.agent.core.model.InputType.VOICE, ingress.captureGlobalGeneration), trace); return
        }
        val route = VoiceRouteCompiler.route(ingress, environment(), appResolver, gate)
        VoiceLatency.recorder.bindRequest(ingress.requestId, trace)
        when (route) {
            VoiceRoute.Stop -> orchestrator.cancelCurrentCommand()
            is VoiceRoute.ConfirmationReply -> completeReview(route, trace)
            is VoiceRoute.Native -> {
                clear(); VoiceLatency.recorder.route(trace, LatencyPath.NATIVE)
                await(orchestrator.taskRuntime.submitVoicePurpose(route.purpose, trace), trace)
            }
            is VoiceRoute.NeedsOwlReview -> {
                clear()
                if (!VoicePurposeAdapter.owlSupported(route.purpose)) { VoiceLatency.recorder.close(trace, LatencyOutcome.NEEDS_USER); say("This exact scope is not supported by Owl. No action was executed."); return }
                if (!com.unoone.agent.phonecontrol.ScreenshotCapture.hasPermission()) {
                    say("Grant screen capture permission in UnoOne, then make a new request. Nothing will run automatically."); VoiceLatency.recorder.close(trace, LatencyOutcome.NEEDS_USER); return
                }
                val now = SystemClock.elapsedRealtime()
                // Review expires in 60 seconds; approved execution has a separately disclosed
                // 180-second ceiling. The former 60-second execution default was below the
                // measured ~72–74s full-prompt host call and would truncate valid Owl work.
                val review = VoiceTaskReview(UUID.randomUUID().toString(), route.purpose, now, now + 60000,
                    maxSteps = route.purpose.steps.size, maxSeconds = 180)
                synchronized(reviewLock) {
                    gate.publish(review); reviewState.value = review; reviewTraces[review.reviewId] = trace
                }
                reviewScope.launch {
                    delay(60000)
                    synchronized(reviewLock) {
                        if (reviewState.value?.reviewId == review.reviewId) clearWithOutcome(LatencyOutcome.TIMED_OUT)
                    }
                }
                VoiceLatency.recorder.mark(trace, LatencyStage.APPROVAL_SHOWN)
                say(VoicePurposeAdapter.description(review))
                gate.markQuestionReady(review.reviewId, SystemClock.elapsedRealtime())
                VoiceLatency.recorder.mark(trace, LatencyStage.APPROVAL_READY)
            }
            VoiceRoute.Conversation -> {
                clear(); await(orchestrator.taskRuntime.submitVoiceConversation(ingress, trace), trace)
            }
            is VoiceRoute.Clarify -> {
                if (VoiceReviewGate.speechDecision(ingress.transcript) != null || ingress.transcript.trim().equals("yes", true)) {
                    statusState.value = route.reason; VoiceLatency.recorder.close(trace, LatencyOutcome.REJECTED); return
                }
                clear()
                if (orchestrator.isDeterministicVoiceRule(ingress.transcript)) await(orchestrator.taskRuntime.submitPreparedCommand(ingress.transcript, com.unoone.agent.core.model.InputType.VOICE, ingress.captureGlobalGeneration), trace)
                else say(route.reason)
            }
            is VoiceRoute.Blocked -> { clear(); say(route.reason); VoiceLatency.recorder.close(trace, LatencyOutcome.REJECTED) }
        }
    }
    suspend fun button(displayed: VoiceTaskReview, decision: ReviewDecision) {
        val reply = gate.consumeButton(displayed.reviewId, displayed.purpose.requestId,
            displayed.purpose.captureGlobalGeneration, decision, environment()) ?: return
        completeReview(reply, null)
    }
    private suspend fun completeReview(reply: VoiceRoute.ConfirmationReply, trace: LatencyToken?) {
        val owned = synchronized(reviewLock) {
            val displayed = reviewState.value ?: return
            if (displayed.reviewId != reply.reviewId || displayed.purpose.requestId != reply.requestId) return
            reviewState.value = null
            displayed to reviewTraces.remove(displayed.reviewId)
        }
        // A spoken approval is its own non-action capture, never the task's identity.
        if (trace != owned.second) VoiceLatency.recorder.close(trace, LatencyOutcome.COMPLETED_NON_ACTION)
        withContext(CurrentLatencyContext(owned.second)) {
            VoiceLatency.recorder.mark(owned.second, LatencyStage.APPROVAL_RESPONSE)
            VoiceLatency.recorder.mark(owned.second, LatencyStage.APPROVAL_RESOLVED)
            try {
                if (reply.decision != ReviewDecision.CONFIRM) {
                    say("Cancelled."); VoiceLatency.recorder.close(owned.second, LatencyOutcome.CANCELLED)
                } else await(orchestrator.taskRuntime.submitVoiceOwl(owned.first, owned.second), owned.second)
            } finally { VoiceLatency.recorder.close(owned.second, LatencyOutcome.INTERRUPTED) }
        }
    }
    private suspend fun await(admission: Admission, trace: LatencyToken?) {
        if (admission is Admission.Rejected) { statusState.value = "Not admitted: ${admission.reason}"; VoiceLatency.recorder.close(trace, LatencyOutcome.REJECTED); return }
        admission as Admission.Accepted
        VoiceLatency.recorder.bindRequest(admission.taskId.value, trace)
        statusState.value = "Queued — not completed"
        val generation = GlobalTaskCancellation.generation
        try {
            val result = orchestrator.taskRuntime.await(admission.taskId)
            statusState.value = orchestrator.taskRuntime.results.value[admission.taskId]?.text ?: result.outcome.name
            if (AgentRuntimeGate.isEnabled() && generation == GlobalTaskCancellation.generation)
                orchestrator.speakVoiceStatus(statusState.value)
            VoiceLatency.recorder.close(trace, if (result.reason == TaskReason.BUDGET_EXHAUSTED) LatencyOutcome.TIMED_OUT else when(result.outcome) {
                TaskOutcome.VERIFIED -> LatencyOutcome.VERIFIED
                TaskOutcome.RESPONDED -> LatencyOutcome.COMPLETED_NON_ACTION
                TaskOutcome.NEEDS_USER -> LatencyOutcome.NEEDS_USER
                TaskOutcome.FAILED -> LatencyOutcome.FAILED
                TaskOutcome.CANCELLED -> LatencyOutcome.CANCELLED
                TaskOutcome.UNVERIFIED -> LatencyOutcome.UNVERIFIED
            })
        } finally { VoiceLatency.recorder.close(trace, LatencyOutcome.INTERRUPTED) }
    }
    private suspend fun say(text: String) { statusState.value = text; orchestrator.speakVoiceStatus(text) }
}
