package com.unoone.agent.core.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
sealed class DeviceAction {
    @Serializable @SerialName("Observe") data object Observe : DeviceAction()
    @Serializable @SerialName("OpenApp") data class OpenApp(val packageName: String) : DeviceAction()
    @Serializable @SerialName("OpenUri") data class OpenUri(val uri: String) : DeviceAction()
    @Serializable @SerialName("ClickNode") data class ClickNode(val snapshotId: String, val nodeId: String) : DeviceAction()
    @Serializable @SerialName("ClickVisualTarget") data class ClickVisualTarget(val snapshotId: String, val targetId: String) : DeviceAction()
    @Serializable @SerialName("LongPressNode") data class LongPressNode(val snapshotId: String, val nodeId: String) : DeviceAction()
    @Serializable @SerialName("SetText") data class SetText(val snapshotId: String, val nodeId: String, val text: String) : DeviceAction()
    @Serializable @SerialName("ClearText") data class ClearText(val snapshotId: String, val nodeId: String) : DeviceAction()
    @Serializable @SerialName("FocusNode") data class FocusNode(val snapshotId: String, val nodeId: String) : DeviceAction()
    @Serializable @SerialName("Scroll") data class Scroll(val snapshotId: String, val nodeId: String, val direction: ScrollDirection) : DeviceAction()
    @Serializable @SerialName("Swipe") data class Swipe(val snapshotId: String, val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMs: Long = 300) : DeviceAction()
    @Serializable @SerialName("Back") data object Back : DeviceAction()
    @Serializable @SerialName("Home") data object Home : DeviceAction()
    @Serializable @SerialName("Recents") data object Recents : DeviceAction()
    @Serializable @SerialName("Notifications") data object Notifications : DeviceAction()
    @Serializable @SerialName("Wait") data class Wait(val durationMs: Long = 350) : DeviceAction()
    @Serializable @SerialName("ReadNode") data class ReadNode(val snapshotId: String, val nodeId: String) : DeviceAction()
    @Serializable @SerialName("ReadScreen") data object ReadScreen : DeviceAction()
    @Serializable @SerialName("AskUser") data class AskUser(val question: String) : DeviceAction()
    @Serializable @SerialName("RequestConfirmation") data class RequestConfirmation(val reason: String) : DeviceAction()
    @Serializable @SerialName("Done") data class Done(val summary: String = "") : DeviceAction()
    @Serializable @SerialName("Fail") data class Fail(val reason: String) : DeviceAction()
    @Serializable @SerialName("Escalate") data class Escalate(val reason: String) : DeviceAction()
}
@Serializable enum class ScrollDirection { FORWARD, BACKWARD }

fun DeviceAction.snapshotRef(): String? = when (this) {
    is DeviceAction.ClickNode -> snapshotId; is DeviceAction.ClickVisualTarget -> snapshotId
    is DeviceAction.LongPressNode -> snapshotId; is DeviceAction.SetText -> snapshotId
    is DeviceAction.ClearText -> snapshotId; is DeviceAction.FocusNode -> snapshotId
    is DeviceAction.Scroll -> snapshotId; is DeviceAction.Swipe -> snapshotId
    is DeviceAction.ReadNode -> snapshotId; else -> null
}
fun DeviceAction.nodeRef(): String? = when (this) {
    is DeviceAction.ClickNode -> nodeId; is DeviceAction.LongPressNode -> nodeId
    is DeviceAction.SetText -> nodeId; is DeviceAction.ClearText -> nodeId
    is DeviceAction.FocusNode -> nodeId; is DeviceAction.Scroll -> nodeId
    is DeviceAction.ReadNode -> nodeId; else -> null
}
object DeviceActionCodec {
    val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false; encodeDefaults = true }
    fun encode(action: DeviceAction): String = json.encodeToString(action)
    fun decode(raw: String, state: PerceptionState, nowMs: Long): DeviceAction {
        require(raw.length <= 8192) { "Action too large" }
        val action = json.decodeFromString<DeviceAction>(raw)
        DeviceActionValidator.validate(action, state, nowMs)
        return action
    }
    /** Unexecuted model advice only: IDs/schema checked against the source, no freshness authority. */
    fun decodeProposal(raw: String, source: PerceptionState): DeviceAction {
        require(raw.length <= 8192) { "Action too large" }
        return json.decodeFromString<DeviceAction>(raw).also { DeviceActionValidator.validateStructure(it, source) }
    }
    fun digest(action: DeviceAction) = UiStateHasher.digest(encode(action))
}
object DeviceActionValidator {
    const val MAX_SNAPSHOT_AGE_MS = 5_000L
    fun validate(action: DeviceAction, state: PerceptionState, nowMs: Long) {
        val s = state.snapshot
        require(nowMs >= s.capturedAtMs && nowMs - s.capturedAtMs <= MAX_SNAPSHOT_AGE_MS) { "Stale snapshot" }
        validateStructure(action, state)
    }
    /** Does not authorize execution or establish that the source observation is current. */
    fun validateStructure(action: DeviceAction, state: PerceptionState) {
        val s = state.snapshot
        action.snapshotRef()?.let { require(it == s.id) { "Wrong snapshot" } }
        val node = action.nodeRef()?.let { requireNotNull(s.node(it)) { "Unknown node" } }
        if (node != null) require(node.visible && node.enabled && s.displayBounds.contains(node.bounds)) { "Unavailable node" }
        when (action) {
            is DeviceAction.ClickNode -> require(node!!.clickable)
            is DeviceAction.LongPressNode -> require(node!!.longClickable)
            is DeviceAction.SetText -> require(node!!.editable && action.text.length <= 2048)
            is DeviceAction.ClearText -> require(node!!.editable)
            is DeviceAction.FocusNode -> require(node!!.focusable)
            is DeviceAction.Scroll -> require(node!!.scrollable)
            is DeviceAction.ClickVisualTarget -> require(state.visualTargets.any { it.id == action.targetId && it.snapshotId == s.id })
            is DeviceAction.Swipe -> require(action.durationMs in 100..1500 && s.displayBounds.contains(action.startX, action.startY) && s.displayBounds.contains(action.endX, action.endY))
            is DeviceAction.Wait -> require(action.durationMs in 0..2000)
            is DeviceAction.OpenApp -> require(action.packageName.length <= 256 && action.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
            is DeviceAction.OpenUri -> { val u = java.net.URI(action.uri); require(action.uri.length <= 2048 && u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null) }
            is DeviceAction.AskUser -> require(action.question.length in 1..1024)
            is DeviceAction.RequestConfirmation -> require(action.reason.length in 1..1024)
            is DeviceAction.Done -> require(action.summary.length <= 1024)
            is DeviceAction.Fail -> require(action.reason.length in 1..1024)
            is DeviceAction.Escalate -> require(action.reason.length in 1..1024)
            else -> Unit
        }
    }
}
