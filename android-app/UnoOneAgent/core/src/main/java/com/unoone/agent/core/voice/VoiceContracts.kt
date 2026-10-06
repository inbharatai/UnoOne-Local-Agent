package com.unoone.agent.core.voice

import java.security.MessageDigest
import java.util.Collections

/** Capture-owned facts. Never restamp these at STT completion or review submission. */
data class VoiceIngress(
    val requestId: String,
    val transcript: String,
    val captureGlobalGeneration: Long,
    val captureStartMono: Long,
    val underlyingAppEvidence: UnderlyingAppEvidence? = null,
    val liveReviewId: String? = null
) {
    init { require(requestId.isNotBlank()); require(captureStartMono >= 0) }
}

/** Native observed scope evidence, NOT reusable execution/foreground proof. */
data class UnderlyingAppEvidence(
    val packageName: String,
    val windowId: Int,
    val observedAtMono: Long,
    val isApplicationWindow: Boolean = true,
    val isOwnOverlay: Boolean = false
)

data class AppChoice(val packageName: String, val label: String) {
    init {
        require(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(packageName))
        require(label.isNotBlank())
    }
}

sealed class AppResolution {
    data class Resolved(val app: AppChoice) : AppResolution()
    object Ambiguous : AppResolution()
    object Unavailable : AppResolution()
}

/** Implement with the installed native AppRegistry, never with model-generated choices. */
fun interface VoiceAppResolver { fun resolve(spokenName: String): AppResolution }

enum class VoiceOperation { OPEN_APP, BACK, HOME, SCROLL_UP, SCROLL_DOWN, READ_SCREEN, CLICK, FOCUS, SELECT_TAB, WRITE }

data class VoiceStep(
    val operation: VoiceOperation,
    val app: AppChoice,
    val exactLabel: String? = null,
    val exactValue: String? = null
)

/** Only compiler-created purposes; defensive list copy prevents post-review scope mutation. */
class NativeVoicePurpose internal constructor(ingress: VoiceIngress, steps: List<VoiceStep>) {
    val requestId = ingress.requestId
    val captureGlobalGeneration = ingress.captureGlobalGeneration
    val captureStartMono = ingress.captureStartMono
    val underlyingAppEvidence = ingress.underlyingAppEvidence
    val steps: List<VoiceStep> = Collections.unmodifiableList(ArrayList(steps))
    // Length framing avoids delimiter collisions in exact user values. Digest is identity, not authority.
    val digest: String = MessageDigest.getInstance("SHA-256").digest(buildString {
        fun field(value: String?) { if (value == null) append("-1:") else append(value.length).append(':').append(value) }
        field(requestId); field(captureGlobalGeneration.toString()); field(captureStartMono.toString())
        field(underlyingAppEvidence?.toString())
        this@NativeVoicePurpose.steps.forEach { field(it.operation.name); field(it.app.packageName); field(it.app.label); field(it.exactLabel); field(it.exactValue) }
    }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class VoiceRouteEnvironment(
    val enabled: Boolean,
    val globalGeneration: Long,
    val nowMono: Long,
    val conversationSupported: Boolean = true,
    val maxEvidenceAgeMs: Long = 15_000L
)

enum class ReviewDecision { CONFIRM, CANCEL }

sealed class VoiceRoute {
    object Stop : VoiceRoute()
    data class Native(val purpose: NativeVoicePurpose) : VoiceRoute()
    data class NeedsOwlReview(val purpose: NativeVoicePurpose) : VoiceRoute()
    object Conversation : VoiceRoute()
    data class Clarify(val reason: String) : VoiceRoute()
    data class Blocked(val reason: String) : VoiceRoute()
    data class ConfirmationReply(val reviewId: String, val requestId: String, val decision: ReviewDecision) : VoiceRoute()
}

/** Display AND narrate this exact purpose, frame permission and budgets before marking ready. */
data class VoiceTaskReview(
    val reviewId: String,
    val purpose: NativeVoicePurpose,
    val createdAtMono: Long,
    val deadlineMono: Long,
    val maxSteps: Int = 8,
    val maxSeconds: Int = 60,
    val allowLocalFrames: Boolean = true
) {
    init {
        require(reviewId.isNotBlank())
        require(createdAtMono >= purpose.captureStartMono && deadlineMono > createdAtMono)
        require(maxSteps in 1..24 && purpose.steps.size <= maxSteps)
        require(maxSeconds in 1..180)
    }
}
