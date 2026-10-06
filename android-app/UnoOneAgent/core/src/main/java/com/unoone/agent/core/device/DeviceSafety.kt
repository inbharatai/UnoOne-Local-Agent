package com.unoone.agent.core.device

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

class DeviceEpoch {
    private val epoch = AtomicLong()
    fun current(): Long = epoch.get()
    fun cancel(): Long = epoch.incrementAndGet()
    fun check(expected: Long) { if (current() != expected) throw CancellationException("Device run superseded") }
}
/** Native authorization, created from the user's intent by app code, NEVER by a model or screen. */
class DeviceAuthorization(
    val observe: Boolean = false,
    allowedPackages: Set<String> = emptySet(),
    allowedHttpsHosts: Set<String> = emptySet(),
    val navigation: Boolean = false,
    allowedUris: Set<String> = emptySet(),
    val nativeActionIntent: (DeviceAction, UiNode) -> Boolean = { _, _ -> false },
    val interactionBindingValid: (DeviceAction, PerceptionState) -> Boolean = { _, _ -> true }
) {
    val allowedUris: Set<String> = java.util.Collections.unmodifiableSet(HashSet(allowedUris))
    val allowedPackages: Set<String> = java.util.Collections.unmodifiableSet(HashSet(allowedPackages))
    val allowedHttpsHosts: Set<String> = java.util.Collections.unmodifiableSet(HashSet(allowedHttpsHosts))
}
/** A provider receipt is bound to the original action; only native equivalence may refresh it. */
class ActionConfirmation private constructor(
    val epoch: Long, val snapshotId: String, val actionDigest: String,
    val semanticActionIdentity: String?, val originalSnapshotId: String,
    val equivalenceStateHash: String?
) {
    constructor(epoch: Long, snapshotId: String, actionDigest: String) :
        this(epoch, snapshotId, actionDigest, null, snapshotId, null)

    companion object {
        /** No model grounding, label matching, or approval copying. One exact native refresh only. */
        internal fun refreshEquivalent(
            receipt: ActionConfirmation?, action: DeviceAction, before: PerceptionState,
            after: PerceptionState, epoch: Long, auth: DeviceAuthorization, nowMs: Long
        ): Pair<DeviceAction, ActionConfirmation>? {
            val old = before.snapshot
            val fresh = after.snapshot
            if (!DeviceSafetyPolicy.confirmationMatches(receipt, action, old, epoch) ||
                receipt!!.equivalenceStateHash != null) return null
            val rebound = NativeProposalRefresh.refreshEquivalent(action, before, after, nowMs) ?: return null
            val fullHash = UiStateHasher.hash(old)
            val target = action.nodeRef()?.let(old::node) ?: return null
            if (target != fresh.node(target.id) || target.semantic != TargetSemantic.FORM_FIELD ||
                target.password || target.packageName !in auth.allowedPackages) return null
            if (DeviceSafetyPolicy.decide(action, before, auth) !is SafetyDecision.Confirm) return null
            if (DeviceSafetyPolicy.decide(rebound, after, auth) !is SafetyDecision.Confirm) return null
            try { DeviceActionValidator.validate(rebound, after, nowMs) }
            catch (_: IllegalArgumentException) { return null }
            // Identity records original exact action (including values), target and full UI state.
            val identity = UiStateHasher.digest(DeviceActionCodec.digest(action) + ":" + target.signature() + ":" + fullHash)
            return rebound to ActionConfirmation(epoch, fresh.id, DeviceActionCodec.digest(rebound),
                identity, old.id, fullHash)
        }
    }
}
/** Exact native equivalence only; returns unapproved advice, never a confirmation receipt. */
internal object NativeProposalRefresh {
    fun refreshEquivalent(action: DeviceAction, before: PerceptionState, after: PerceptionState, nowMs: Long): DeviceAction? {
        val old = before.snapshot
        val fresh = after.snapshot
        if (old.truncated || fresh.truncated || old.id == fresh.id ||
            fresh.capturedAtMs < old.capturedAtMs || fresh.eventSequence < old.eventSequence ||
            old.displayBounds != fresh.displayBounds) return null
        val fullHash = UiStateHasher.hash(old)
        if (fullHash != UiStateHasher.hash(fresh)) return null
        // Exact field equality supplements the full native hash (including package/resource/bounds).
        if (old.nodes != fresh.nodes || old.windows.size != fresh.windows.size ||
            old.windows.zip(fresh.windows).any { (a, b) ->
                a.id != b.id || a.packageName != b.packageName || a.bounds != b.bounds || a.nodes != b.nodes
            }) return null
        val rebound = when (action) {
            is DeviceAction.SetText -> action.copy(snapshotId = fresh.id)
            is DeviceAction.ClearText -> action.copy(snapshotId = fresh.id)
            is DeviceAction.ClickNode -> action.copy(snapshotId = fresh.id)
            is DeviceAction.LongPressNode -> action.copy(snapshotId = fresh.id)
            is DeviceAction.FocusNode -> action.copy(snapshotId = fresh.id)
            is DeviceAction.Scroll -> action.copy(snapshotId = fresh.id)
            is DeviceAction.ReadNode -> action.copy(snapshotId = fresh.id)
            // Visual/coordinate proposals have no exact native semantic target proof.
            is DeviceAction.ClickVisualTarget, is DeviceAction.Swipe -> return null
            else -> action
        }
        try {
            DeviceActionValidator.validateStructure(action, before)
            DeviceActionValidator.validate(rebound, after, nowMs)
        } catch (_: IllegalArgumentException) { return null }
        return rebound
    }
}
sealed class SafetyDecision {
    data object Allow : SafetyDecision()
    data class Confirm(val reason: String) : SafetyDecision()
    data class Handover(val reason: String) : SafetyDecision()
}
object DeviceSafetyPolicy {
    private val forbidden = setOf(TargetSemantic.SECRET, TargetSemantic.PAYMENT, TargetSemantic.OTP,
        TargetSemantic.CAPTCHA, TargetSemantic.LEGAL, TargetSemantic.FINAL_SEND, TargetSemantic.DESTRUCTIVE, TargetSemantic.SECURITY)
    // Defense in depth only. Missing these words is never semantic proof of safety.
    private val sensitive = Regex("(?i)password|passcode|\\botp\\b|verification.code|captcha|credit.card|\\bpay\\b|purchase|checkout|\\bsend\\b|submit|accept.terms|delete|transfer|sign.in|log.in")
    fun isSensitiveMetadata(value: CharSequence?): Boolean = value != null &&
        (sensitive.containsMatchIn(value) || SensitiveReadRedaction.hasSecretLabel(value) ||
            Regex("(?i)payment|billing|authenticat|authoriz|sign.?in|log.?in|sign.?up|two.factor|2fa|mfa|consent|agreement|terms|captcha|\\bauth\\b|confirm.purchase").containsMatchIn(value))
    fun decide(action: DeviceAction, state: PerceptionState, auth: DeviceAuthorization): SafetyDecision {
        if (!auth.observe) return SafetyDecision.Handover("Observation is not authorized")
        if (!auth.interactionBindingValid(action, state)) return SafetyDecision.Handover("Interaction owner or snapshot binding expired")
        val s = state.snapshot
        if (action is DeviceAction.OpenApp) return if (action.packageName in auth.allowedPackages) SafetyDecision.Allow else SafetyDecision.Handover("Package outside user scope")
        if (action is DeviceAction.OpenUri) return if (action.uri in auth.allowedUris && java.net.URI(action.uri).host in auth.allowedHttpsHosts && !sensitive.containsMatchIn(action.uri)) SafetyDecision.Allow else SafetyDecision.Handover("Exact URI not natively authorized or sensitive")
        if (action is DeviceAction.Swipe || action is DeviceAction.ClickVisualTarget)
            return SafetyDecision.Handover("Coordinate/visual action has no native semantic proof")
        val node = action.nodeRef()?.let(s::node)
        if (action.nodeRef() != null && node == null) return SafetyDecision.Handover("Unknown node")
        if (node != null) {
            if (node.packageName !in auth.allowedPackages) return SafetyDecision.Handover("Target outside user scope")
            if (node.password || node.semantic in forbidden || isSensitiveMetadata(node.text + " " + node.description + " " + node.resourceId + " " + node.hint))
                return SafetyDecision.Handover("Sensitive target requires manual takeover")
            if (action is DeviceAction.ReadNode) return SafetyDecision.Allow
            if (node.semantic == TargetSemantic.UNKNOWN) return SafetyDecision.Handover("Unknown target semantics")
            if (action is DeviceAction.SetText || action is DeviceAction.ClearText) {
                if (node.semantic != TargetSemantic.FORM_FIELD) return SafetyDecision.Handover("Unproven form field")
                if (action is DeviceAction.SetText && sensitive.containsMatchIn(action.text)) return SafetyDecision.Handover("Potential secret value")
                if (auth.nativeActionIntent(action, node)) return SafetyDecision.Allow
                return SafetyDecision.Confirm("Review this exact field edit before applying")
            }
            if (node.semantic == TargetSemantic.FORM_FIELD) return if (auth.nativeActionIntent(action, node)) SafetyDecision.Allow else SafetyDecision.Confirm("Review form interaction")
            if (!auth.nativeActionIntent(action, node)) return SafetyDecision.Handover("Action target is not authorized by a native user goal")
            return SafetyDecision.Allow
        }
        return when (action) {
            DeviceAction.Back, DeviceAction.Home, DeviceAction.Recents, DeviceAction.Notifications ->
                if (auth.navigation) SafetyDecision.Allow else SafetyDecision.Handover("Navigation outside user scope")
            else -> SafetyDecision.Allow
        }
    }
    fun confirmationMatches(c: ActionConfirmation?, action: DeviceAction, snapshot: UiSnapshot, epoch: Long) =
        c != null && c.epoch == epoch && c.snapshotId == snapshot.id && c.actionDigest == DeviceActionCodec.digest(action) &&
            (c.equivalenceStateHash == null || (c.semanticActionIdentity != null && c.equivalenceStateHash == UiStateHasher.hash(snapshot)))
}
