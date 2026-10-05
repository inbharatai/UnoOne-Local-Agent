package com.unoone.agent

import com.unoone.agent.core.device.*

/** Model proposals never add authority or supply query/recipient values. */
internal object NativeGoalPolicy {
    const val TOTAL_MAX = 32
    fun reviewedField(node: UiNode, pkg: String): Boolean =
        node.packageName == pkg && !node.password && node.editable &&
            node.semantic == TargetSemantic.FORM_FIELD &&
            NativeReviewedTargets.semantic(pkg, node.resourceId, node.className) == TargetSemantic.FORM_FIELD

    fun reviewedSearchButton(node: UiNode, pkg: String): Boolean = node.packageName == pkg && !node.password &&
        node.enabled && node.clickable && !node.editable && node.semantic == TargetSemantic.NAVIGATION &&
        NativeReviewedTargets.semantic(pkg, node.resourceId, node.className) == TargetSemantic.NAVIGATION &&
        (node.resourceId == "android:id/search_button" || node.resourceId == "com.whatsapp:id/menuitem_search")

    fun navigation(action: DeviceAction, state: PerceptionState, pkg: String): DeviceAction {
        val allowed = when (action) {
            is DeviceAction.Wait, DeviceAction.Observe, DeviceAction.ReadScreen -> true
            is DeviceAction.FocusNode ->
                action.nodeRef()?.let(state.snapshot::node)?.let { reviewedField(it, pkg) } == true
            is DeviceAction.ClickNode -> action.nodeRef()?.let(state.snapshot::node)?.let { reviewedSearchButton(it, pkg) } == true
            else -> false
        }
        return if (allowed && state.snapshot.windows.firstOrNull()?.packageName == pkg) action
        else DeviceAction.AskUser("No source-reviewed navigation within requested app")
    }
}
