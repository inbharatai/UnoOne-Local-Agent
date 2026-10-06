package com.unoone.agent.core.device

import com.unoone.agent.core.task.TaskCapability
import com.unoone.agent.core.task.TaskId
import com.unoone.agent.core.task.TaskScope

/** Native policy, never inferred from model proposals or screen instructions. */
enum class NativeTargetMatchMode { EXACT, CASE_INSENSITIVE_UNIQUE }

/** Only trusted native voice compilation opts into case-insensitive target labels. */
data class NativeTargetSelector(
    val exactLabel: String,
    val rowNumber: Int? = null,
    val matchMode: NativeTargetMatchMode = NativeTargetMatchMode.EXACT
) {
    init { require(exactLabel.isNotBlank() && exactLabel.length <= 256); require(rowNumber == null || rowNumber > 0) }
}
enum class ReviewedOperation { CLICK, SELECT_TAB, FOCUS, WRITE }
data class ReviewedInteraction(val selector: NativeTargetSelector, val operation: ReviewedOperation, val exactValue: String? = null) {
    init {
        require((operation == ReviewedOperation.WRITE) == (exactValue != null))
        require(exactValue == null || exactValue.length <= 256)
    }
}
/** Native task identity, not a model-supplied authorization ticket. */
data class InteractionOwner(val taskId: TaskId, val taskEpoch: Long, val stopGeneration: Long, val step: Long)

object NativeTargetResolver {
    private fun matches(label: String, selector: NativeTargetSelector): Boolean = when (selector.matchMode) {
        NativeTargetMatchMode.EXACT -> label == selector.exactLabel
        NativeTargetMatchMode.CASE_INSENSITIVE_UNIQUE -> label.equals(selector.exactLabel, ignoreCase = true)
    }

    /** No fuzzy labels, parent promotion, coordinates, recipient inference, or truncated-tree uniqueness. */
    fun resolve(selector: NativeTargetSelector, state: PerceptionState, pkg: String, nowMs: Long): UiNode? {
        val s = state.snapshot
        if (s.truncated || s.windows.firstOrNull()?.packageName != pkg || nowMs < s.capturedAtMs ||
            nowMs - s.capturedAtMs > DeviceActionValidator.MAX_SNAPSHOT_AGE_MS) return null
        return s.nodes.filter { it.packageName == pkg && it.visible && it.enabled &&
            s.displayBounds.contains(it.bounds) && (matches(it.text, selector) || matches(it.description, selector) || matches(it.hint, selector)) &&
            (selector.rowNumber == null || it.collectionRowIndex == selector.rowNumber - 1) }.singleOrNull()
    }
}

/** Binds one exact operation/value to one native snapshot and one live task owner. */
class BoundReviewedInteraction private constructor(
    val owner: InteractionOwner, val request: ReviewedInteraction, val packageName: String,
    val before: PerceptionState, val target: UiNode, val action: DeviceAction
) {
    fun authorization(scope: TaskScope, currentOwner: () -> InteractionOwner?): DeviceAuthorization =
        DeviceAuthorization(observe = scopeAllows(scope, packageName), allowedPackages = if (scopeAllows(scope, packageName)) setOf(packageName) else emptySet(),
            nativeActionIntent = { candidate, node ->
                currentOwner() == owner && scopeAllows(scope, packageName) && candidate == action &&
                    node == target && candidate.snapshotRef() == before.snapshot.id
            }, interactionBindingValid = { candidate, state ->
                currentOwner() == owner && scopeAllows(scope, packageName) && candidate == action &&
                    state.snapshot === before.snapshot
            })

    /** Call only after accepted dispatch and a fresh native observation. Dispatch acceptance is not success. */
    fun verified(after: PerceptionState, currentOwner: InteractionOwner, nowMs: Long): Boolean {
        val old = before.snapshot; val fresh = after.snapshot
        if (currentOwner != owner || fresh.truncated || old.id == fresh.id || fresh.capturedAtMs < old.capturedAtMs ||
            fresh.eventSequence <= old.eventSequence || nowMs < fresh.capturedAtMs ||
            nowMs - fresh.capturedAtMs > DeviceActionValidator.MAX_SNAPSHOT_AGE_MS ||
            fresh.windows.firstOrNull()?.packageName != packageName) return false
        // Identity must survive independently of an edited label/value.
        val node = fresh.nodes.filter { it.packageName == packageName && it.windowId == target.windowId &&
            it.path == target.path && it.resourceId == target.resourceId && it.className == target.className &&
            it.visible && it.enabled && !it.password && !it.semantic.sensitiveObservation() }.singleOrNull()
        return when (request.operation) {
            ReviewedOperation.WRITE -> target.text != request.exactValue && node?.semantic == TargetSemantic.FORM_FIELD && node.text == request.exactValue
            ReviewedOperation.FOCUS -> !target.focused && node?.focused == true
            ReviewedOperation.SELECT_TAB -> !target.selected && node?.selected == true && node.semantic == TargetSemantic.NAVIGATION
            ReviewedOperation.CLICK -> {
                // Search needs a NEW usable field; an already present field cannot verify a no-op click.
                val oldFields = old.nodes.filter { it.semantic == TargetSemantic.FORM_FIELD }.map { it.resourceId to it.path }.toSet()
                val newField = fresh.nodes.any { it.packageName == packageName && it.visible && it.enabled && it.editable &&
                    it.semantic == TargetSemantic.FORM_FIELD && (it.resourceId to it.path) !in oldFields }
                val selected = !target.selected && node?.selected == true
                val oldReadable = old.nodes.filter { !it.editable }.map { it.text to it.description }.toSet()
                val newReadable = fresh.nodes.any { it.packageName == packageName && it.visible && !it.editable &&
                    !it.semantic.sensitiveObservation() && (it.text.isNotBlank() || it.description.isNotBlank()) &&
                    (it.text to it.description) !in oldReadable }
                newField || selected || (node == null && newReadable)
            }
        }
    }

    companion object {
        private fun scopeAllows(scope: TaskScope, pkg: String) = pkg in scope.packages &&
            TaskCapability.UI_READ in scope.capabilities && TaskCapability.UI_WRITE in scope.capabilities

        /** Null means handover, NOT a request to ask the model for confidence. */
        fun bind(request: ReviewedInteraction, pkg: String, state: PerceptionState, owner: InteractionOwner,
            scope: TaskScope, nowMs: Long): BoundReviewedInteraction? {
            if (!scopeAllows(scope, pkg)) return null
            val node = NativeTargetResolver.resolve(request.selector, state, pkg, nowMs) ?: return null
            if (node.password || node.semantic == TargetSemantic.UNKNOWN || node.semantic.sensitiveObservation()) return null
            val action = when (request.operation) {
                ReviewedOperation.CLICK -> if (node.clickable && !node.editable && node.semantic in setOf(TargetSemantic.NAVIGATION, TargetSemantic.ORDINARY_CONTROL)) DeviceAction.ClickNode(state.snapshot.id, node.id) else return null
                ReviewedOperation.SELECT_TAB -> if (node.clickable && node.readableTab && node.semantic == TargetSemantic.NAVIGATION) DeviceAction.ClickNode(state.snapshot.id, node.id) else return null
                ReviewedOperation.FOCUS -> if (node.focusable && node.editable && node.semantic == TargetSemantic.FORM_FIELD) DeviceAction.FocusNode(state.snapshot.id, node.id) else return null
                ReviewedOperation.WRITE -> if (node.editable && node.semantic == TargetSemantic.FORM_FIELD) DeviceAction.SetText(state.snapshot.id, node.id, request.exactValue!!) else return null
            }
            val bound = BoundReviewedInteraction(owner, request, pkg, state, node, action)
            if (runCatching { DeviceActionValidator.validate(action, state, nowMs) }.isFailure ||
                DeviceSafetyPolicy.decide(action, state, bound.authorization(scope) { owner }) !is SafetyDecision.Allow) return null
            return bound
        }
    }
}
