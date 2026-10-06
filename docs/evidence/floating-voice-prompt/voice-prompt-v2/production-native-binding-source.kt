package com.unoone.agent.core.guiowl

import com.unoone.agent.core.device.*
import kotlin.math.abs
import kotlin.math.floor

/** Native-only authority, never deserialize from model output. Each target includes its current signature. */
data class OwlScope(val taskId: String, val epoch: Long, val scopeVersion: Long,
    val packageName: String, val windowId: Int, val approvedTargets: Map<String, String>,
    val clickNodeId: String? = null, val focusNodeId: String? = null,
    val writeNodeId: String? = null, val exactWriteValue: String? = null,
    val verticalScrollNodeId: String? = null, val allowBack: Boolean = false,
    val allowHome: Boolean = false, val allowWait: Boolean = false)

/** Receipt must be created after real pixel decoding, consent/redaction and source checks by native caller.
 * Version 1 supports ONLY unpadded, uncropped full-frame resize with unchanged orientation.
 */
data class OwlCaptureReceipt(val taskId: String, val epoch: Long, val scopeVersion: Long,
    val snapshotId: String, val snapshotHash: String, val eventSequence: Long,
    val capturedAtMs: Long, val imageSha256: String, val imageWidth: Int, val imageHeight: Int,
    val displayBounds: RectData, val rotation: Int, val currentRotation: Int,
    val fullFrameUnpadded: Boolean, val consented: Boolean, val privacyReviewed: Boolean,
    val packageName: String, val windowId: Int)

sealed class OwlBindingResult {
    /** Still only a proposal: existing validator, safety, lease and dispatch guard MUST run. */
    data class Bound(val action: DeviceAction, val snapshotHash: String, val taskId: String,
        val epoch: Long, val scopeVersion: Long, val eventSequence: Long,
        val imageSha256: String, val nodeSignature: String?) : OwlBindingResult()
    data class NeedsUser(val reason: String) : OwlBindingResult()
    data class ModelDone(val status: String) : OwlBindingResult()
}

object OwlNativeBinding {
    fun translate(raw: String, snapshot: UiSnapshot, receipt: OwlCaptureReceipt,
        scope: OwlScope, nowMs: Long): OwlBindingResult = try {
        bind(OwlOutputCodec.decode(raw), snapshot, receipt, scope, nowMs)
    } catch (_: IllegalArgumentException) { OwlBindingResult.NeedsUser("Invalid or inadmissible Owl proposal")
    } catch (_: IllegalStateException) { OwlBindingResult.NeedsUser("Invalid or unsupported Owl proposal") }

    /** Non-executable structural source proposal; never substitutes a clock or fresh image. */
    class SourceProposal internal constructor(internal val proposal: OwlProposal, internal val source: UiSnapshot,
        internal val receipt: OwlCaptureReceipt, internal val scope: OwlScope)

    fun propose(raw: String, source: UiSnapshot, receipt: OwlCaptureReceipt, scope: OwlScope): SourceProposal {
        val p = OwlOutputCodec.decode(raw)
        bind(p, source, receipt, scope, receipt.capturedAtMs, structuralOnly = true)
        return SourceProposal(p, source, receipt, scope)
    }

    /** Fresh native proof is required even when inference took minutes. Original provenance stays intact. */
    fun refresh(proposal: SourceProposal, fresh: UiSnapshot, currentScope: OwlScope, nowMs: Long): OwlBindingResult = try {
        require(currentScope == proposal.scope)
        require(fresh.displayBounds == proposal.source.displayBounds && !fresh.truncated)
        require(fresh.capturedAtMs >= proposal.source.capturedAtMs && fresh.eventSequence >= proposal.source.eventSequence)
        require(nowMs >= fresh.capturedAtMs && nowMs - fresh.capturedAtMs <= 5000)
        require(UiStateHasher.hash(fresh) == UiStateHasher.hash(proposal.source))
        val structural = bind(proposal.proposal, proposal.source, proposal.receipt, proposal.scope, nowMs, structuralOnly = true)
        if (structural is OwlBindingResult.Bound) {
            val action = when (val old = structural.action) {
                is DeviceAction.ClickNode -> old.copy(snapshotId = fresh.id)
                is DeviceAction.FocusNode -> old.copy(snapshotId = fresh.id)
                is DeviceAction.SetText -> old.copy(snapshotId = fresh.id)
                is DeviceAction.Scroll -> old.copy(snapshotId = fresh.id)
                else -> old
            }
            DeviceActionValidator.validate(action, PerceptionState(fresh), nowMs)
            structural.copy(action = action, snapshotHash = UiStateHasher.hash(fresh), eventSequence = fresh.eventSequence)
        } else structural
    } catch (_: Exception) { OwlBindingResult.NeedsUser("Native refresh not exactly equivalent; no action") }

    private fun bind(p: OwlProposal, s: UiSnapshot, r: OwlCaptureReceipt, a: OwlScope, now: Long, structuralOnly: Boolean = false): OwlBindingResult {
        require(a.taskId.isNotBlank() && a.epoch >= 0 && a.scopeVersion >= 0)
        require(r.taskId == a.taskId && r.epoch == a.epoch && r.scopeVersion == a.scopeVersion)
        require(r.consented && r.privacyReviewed && r.fullFrameUnpadded && !s.truncated)
        require(r.imageSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(r.snapshotId == s.id && r.snapshotHash == UiStateHasher.hash(s) && r.eventSequence == s.eventSequence)
        require(r.capturedAtMs == s.capturedAtMs)
        if (!structuralOnly) require(now >= r.capturedAtMs && now - r.capturedAtMs <= 5000)
        require(r.displayBounds == s.displayBounds && r.rotation in setOf(0, 90, 180, 270) && r.rotation == r.currentRotation)
        require(r.imageWidth in 1..32768 && r.imageHeight in 1..32768)
        val width = s.displayBounds.right - s.displayBounds.left
        val height = s.displayBounds.bottom - s.displayBounds.top
        require(r.imageWidth.toLong() * height == r.imageHeight.toLong() * width)
        require(r.packageName == a.packageName && r.windowId == a.windowId)
        // Fail closed on other windows, including IME and overlays; no z-order guess.
        require(s.windows.size == 1 && s.windows.single().id == a.windowId && s.windows.single().packageName == a.packageName)
        require(s.nodes.all { it.packageName == a.packageName && it.windowId == a.windowId })
        require(s.nodes.none { it.password || it.semantic.sensitiveObservation() })
        fun safe(n: UiNode) = n.visible && n.enabled && !n.password &&
            n.semantic in setOf(TargetSemantic.NAVIGATION, TargetSemantic.ORDINARY_CONTROL, TargetSemantic.FORM_FIELD)
        fun approved(n: UiNode) = a.approvedTargets[n.id] == n.signature()
        fun target(id: String?): UiNode {
            val n = s.node(requireNotNull(id)) ?: error("Missing approved node")
            require(safe(n) && approved(n)); return n
        }
        fun xy(pt: OwlPoint): Pair<Int, Int> {
            val x = s.displayBounds.left + floor(pt.x * width / 1000.0).toInt()
            val y = s.displayBounds.top + floor(pt.y * height / 1000.0).toInt()
            require(s.displayBounds.contains(x, y)); return x to y
        }
        fun at(pt: OwlPoint, capability: (UiNode) -> Boolean): UiNode {
            val (x, y) = xy(pt)
            // Count ALL eligible native controls, not only approved ones; ambiguity is never filtered away.
            val candidates = s.nodes.filter { it.visible && it.enabled && capability(it) && it.bounds.contains(x, y) }
            val n = candidates.singleOrNull() ?: error("Ambiguous native point")
            require(safe(n) && approved(n))
            require(n.path.isNotBlank() && n.bounds != s.displayBounds)
            val area = (n.bounds.right - n.bounds.left).toLong() * (n.bounds.bottom - n.bounds.top)
            require(area * 100 < width.toLong() * height * 90)
            return n
        }
        var node: UiNode? = null
        val action: DeviceAction = when (p.action) {
            "click" -> {
                val n = at(requireNotNull(p.coordinate)) { it.clickable || it.focusable }; node = n
                when {
                    n.id == a.clickNodeId && n.clickable -> DeviceAction.ClickNode(s.id, n.id)
                    n.id == a.focusNodeId && n.focusable -> DeviceAction.FocusNode(s.id, n.id)
                    else -> error("Point does not name explicit approved click/focus")
                }
            }
            "type" -> {
                val n = target(a.writeNodeId); node = n
                require(n.editable && n.focused && n.semantic == TargetSemantic.FORM_FIELD)
                require(s.nodes.filter { it.visible && it.enabled && it.focused && it.editable }.singleOrNull()?.id == n.id)
                require(a.exactWriteValue != null && p.text == a.exactWriteValue)
                DeviceAction.SetText(s.id, n.id, requireNotNull(p.text))
            }
            "swipe" -> {
                val n = target(a.verticalScrollNodeId); node = n; require(n.scrollable)
                val start = xy(requireNotNull(p.coordinate)); val end = xy(requireNotNull(p.coordinate2))
                require(n.bounds.contains(start.first, start.second) && n.bounds.contains(end.first, end.second))
                require(start.first == end.first && abs(end.second - start.second) >= 1)
                require(s.nodes.filter { it.visible && it.enabled && it.scrollable && it.bounds.contains(start.first, start.second) }.singleOrNull()?.id == n.id)
                // Native scope grants conventional vertical ancestor semantics, not arbitrary drag/swipe.
                DeviceAction.Scroll(s.id, n.id, if (end.second < start.second) ScrollDirection.FORWARD else ScrollDirection.BACKWARD)
            }
            "system_button" -> when {
                p.button == "Back" && a.allowBack -> DeviceAction.Back
                p.button == "Home" && a.allowHome -> DeviceAction.Home
                else -> error("Navigation outside native scope")
            }
            "wait" -> { require(a.allowWait); DeviceAction.Wait((requireNotNull(p.time) * 1000).toLong()) }
            "terminate", "answer" -> return OwlBindingResult.ModelDone(p.status ?: "answer")
            else -> return OwlBindingResult.NeedsUser("Action requires user handover")
        }
        if (!structuralOnly) DeviceActionValidator.validate(action, PerceptionState(s), now)
        return OwlBindingResult.Bound(action, r.snapshotHash, a.taskId, a.epoch, a.scopeVersion,
            s.eventSequence, r.imageSha256, node?.signature())
    }
}
