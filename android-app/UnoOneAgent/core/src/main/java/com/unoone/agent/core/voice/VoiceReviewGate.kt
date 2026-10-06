package com.unoone.agent.core.voice

/** A single live review, consumed atomically; not a reusable confirmation boolean. */
class VoiceReviewGate {
    private var review: VoiceTaskReview? = null
    private var questionReadyAtMono: Long? = null
    private val publishedIds = HashSet<String>()

    @Synchronized fun publish(review: VoiceTaskReview) {
        // Never recycle a nonce within this gate's lifetime, including after cancel/expiry.
        require(publishedIds.add(review.reviewId)) { "A review ID cannot be reused" }
        this.review = review
        questionReadyAtMono = null
    }

    @Synchronized fun markQuestionReady(reviewId: String, readyAtMono: Long): Boolean {
        val pending = review ?: return false
        if (pending.reviewId != reviewId || readyAtMono < pending.createdAtMono || readyAtMono >= pending.deadlineMono || questionReadyAtMono != null) return false
        questionReadyAtMono = readyAtMono
        return true
    }

    @Synchronized fun pendingReview(): VoiceTaskReview? = review
    @Synchronized fun clear() { review = null; questionReadyAtMono = null }

    @Synchronized fun consumeSpeech(ingress: VoiceIngress, environment: VoiceRouteEnvironment): VoiceRoute.ConfirmationReply? {
        val decision = speechDecision(ingress.transcript) ?: return null
        val pending = valid(environment) ?: return null
        val ready = questionReadyAtMono ?: return null
        if (ingress.liveReviewId != pending.reviewId || ingress.captureGlobalGeneration != pending.purpose.captureGlobalGeneration ||
            ingress.captureStartMono <= ready || ingress.captureStartMono > environment.nowMono || ingress.captureStartMono >= pending.deadlineMono) return null
        return consume(pending, decision)
    }

    @Synchronized fun consumeButton(
        reviewId: String,
        requestId: String,
        capturedGeneration: Long,
        decision: ReviewDecision,
        environment: VoiceRouteEnvironment
    ): VoiceRoute.ConfirmationReply? {
        val pending = valid(environment) ?: return null
        val ready = questionReadyAtMono ?: return null
        if (environment.nowMono < ready || reviewId != pending.reviewId || requestId != pending.purpose.requestId ||
            capturedGeneration != pending.purpose.captureGlobalGeneration) return null
        return consume(pending, decision)
    }

    private fun valid(environment: VoiceRouteEnvironment): VoiceTaskReview? {
        val pending = review ?: return null
        if (!environment.enabled || environment.globalGeneration != pending.purpose.captureGlobalGeneration || environment.nowMono >= pending.deadlineMono) {
            clear(); return null
        }
        if (environment.nowMono < pending.createdAtMono) return null
        return pending
    }

    private fun consume(pending: VoiceTaskReview, decision: ReviewDecision): VoiceRoute.ConfirmationReply {
        clear()
        return VoiceRoute.ConfirmationReply(pending.reviewId, pending.purpose.requestId, decision)
    }

    companion object {
        fun speechDecision(text: String): ReviewDecision? = when (text.trim().lowercase(java.util.Locale.ROOT)) {
            "confirm" -> ReviewDecision.CONFIRM
            "no", "cancel" -> ReviewDecision.CANCEL
            else -> null
        }
    }
}
