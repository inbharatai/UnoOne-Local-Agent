package com.unoone.agent.accessibilitycontrol

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.unoone.agent.core.device.*
import com.unoone.agent.core.runtime.AgentRuntimeGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** Native, reviewed package/resource-id rules only. Do not implement this with an LLM or page labels. */
fun interface NativeSemanticResolver {
    fun resolve(packageName: String, resourceId: String, className: String): TargetSemantic
}

/** Real Android adapter. No live AccessibilityNodeInfo escapes capture or execution. */
class AndroidDeviceAdapter(
    private val service: UnoOneAccessibilityService,
    private val semantics: NativeSemanticResolver = NativeSemanticResolver { _, _, _ -> TargetSemantic.UNKNOWN }
) : DeviceAdapter {
    private var issuedSnapshot: UiSnapshot? = null
    override suspend fun observe(): PerceptionState = withContext(Dispatchers.Main.immediate) {
        check(AgentRuntimeGate.isEnabled()) { "Master disabled" }
        PerceptionState(captureSnapshot())
    }

    /** Synchronous compatibility capture for existing service read API; callers normally use observe(). */
    @Suppress("DEPRECATION")
    internal fun captureSnapshot(): UiSnapshot {
        check(AgentRuntimeGate.isEnabled())
        val metrics = service.resources.displayMetrics
        val display = RectData(0, 0, metrics.widthPixels.coerceIn(1, 32768), metrics.heightPixels.coerceIn(1, 32768))
        val capturedSequence = service.eventSequence
        val root = service.rootInActiveWindow ?: return UiSnapshot(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), capturedSequence, display, emptyList()).also { issuedSnapshot = it }
        val nodes = ArrayList<UiNode>()
        var truncated = false
        var visited = 0
        val windowId = root.windowId
        val pkg = clean(root.packageName)
        // First pass classifies labels before ANY node text enters the immutable snapshot.
        var secretContext = false
        var scanned = 0
        fun scan(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > PerceptionLimits.DEPTH || scanned++ >= PerceptionLimits.NODES) { secretContext = true; return }
            if (node.isPassword || hasSecretMetadata(node)) secretContext = true
            for (i in 0 until node.childCount.coerceAtMost(PerceptionLimits.NODES)) {
                node.getChild(i)?.let { child -> try { scan(child, depth + 1) } finally { child.recycle() } }
            }
        }
        scan(root, 0)
        fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (depth > PerceptionLimits.DEPTH || visited >= PerceptionLimits.NODES) { truncated = true; return }
            visited++
            val described = if (node.isVisibleToUser) describe(node, path, display, secretContext) else null
            described?.let(nodes::add)
            if (node.isPassword || hasSecretMetadata(node) || described?.semantic?.sensitiveObservation() == true || semantics.resolve(clean(node.packageName), clean(node.viewIdResourceName), clean(node.className)).sensitiveObservation()) return // Do not collect potentially leaked descendants either.
            val count = node.childCount.coerceAtMost(PerceptionLimits.NODES)
            if (node.childCount > count) truncated = true
            for (i in 0 until count) {
                if (visited >= PerceptionLimits.NODES) { truncated = true; break }
                node.getChild(i)?.let { child -> try { walk(child, "$path.$i", depth + 1) } finally { child.recycle() } }
            }
        }
        try { walk(root, "0", 0) } finally { root.recycle() }
        check(AgentRuntimeGate.isEnabled())
        return UiSnapshot(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), capturedSequence, display,
            listOf(UiWindow(windowId, pkg, display, nodes)), truncated).also { issuedSnapshot = it }
    }

    private fun clean(value: CharSequence?): String = value?.toString()?.filter { !it.isISOControl() || it == '\n' }?.take(PerceptionLimits.TEXT).orEmpty()
    private fun hasSecretMetadata(node: AccessibilityNodeInfo): Boolean =
        listOf(node.viewIdResourceName, node.text, node.contentDescription,
            if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText else null).any(SensitiveReadRedaction::hasSecretLabel)

    private fun describe(node: AccessibilityNodeInfo, path: String, display: RectData, secretContext: Boolean = false): UiNode? {
        val r = Rect(); node.getBoundsInScreen(r)
        val l = r.left.coerceIn(display.left, display.right); val t = r.top.coerceIn(display.top, display.bottom)
        val right = r.right.coerceIn(display.left, display.right); val bottom = r.bottom.coerceIn(display.top, display.bottom)
        if (right <= l || bottom <= t) return null
        val pkg = clean(node.packageName); val cls = clean(node.className); val resource = clean(node.viewIdResourceName)
        val labelledBy = node.labeledBy
        val labelledSecret = try { labelledBy?.let(::hasSecretMetadata) == true } finally { labelledBy?.recycle() }
        val parent = node.parent
        val neighboringSecret = try {
            parent != null && (hasSecretMetadata(parent) || (0 until parent.childCount.coerceAtMost(PerceptionLimits.NODES)).any { i ->
                parent.getChild(i)?.let { sibling -> try { hasSecretMetadata(sibling) } finally { sibling.recycle() } } == true
            })
        } finally { parent?.recycle() }
        val deny = node.isPassword || hasSecretMetadata(node) || labelledSecret || neighboringSecret ||
            SensitiveReadRedaction.shouldRedact(resource, node.text, secretContext, node.isEditable) ||
            SensitiveReadRedaction.shouldRedact(resource, node.contentDescription, secretContext, node.isEditable)
        val semantic = if (deny) TargetSemantic.SECRET else semantics.resolve(pkg, resource, cls)
        val redact = node.isPassword || semantic.sensitiveObservation()
        return UiNode("${node.windowId}:$path", node.windowId, path, pkg, cls, resource,
            if (redact) "" else clean(node.text), if (redact) "" else clean(node.contentDescription),
            RectData(l, t, right, bottom), node.isClickable, node.isLongClickable, node.isEditable, node.isScrollable,
            node.isFocusable, node.isFocused, node.isEnabled, node.isPassword,
            semantic)
    }

    override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch = withContext(Dispatchers.Main.immediate) {
        guard.validate(action, state, SystemClock.elapsedRealtime())
        check(AgentRuntimeGate.isEnabled())
        if (state.snapshot !== issuedSnapshot) return@withContext DeviceDispatch(false, "Snapshot was not issued by this adapter")
        // Captured event sequence must still match even when original labels/bounds look identical.
        if (service.eventSequence != state.snapshot.eventSequence) return@withContext DeviceDispatch(false, "Stale event sequence")
        issuedSnapshot = null // Consume once, including rejected/uncertain dispatches; never replay.
        val accepted = when (action) {
            DeviceAction.Observe, DeviceAction.ReadScreen -> true
            is DeviceAction.Wait -> { guard.check(); delay(action.durationMs); guard.check(); true }
            is DeviceAction.OpenApp -> {
                val intent = service.packageManager.getLaunchIntentForPackage(action.packageName)
                if (intent == null) false else { guard.check(); check(AgentRuntimeGate.isEnabled()); service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            }
            is DeviceAction.OpenUri -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                guard.check(); check(AgentRuntimeGate.isEnabled()); service.startActivity(intent); true
            }
            DeviceAction.Back -> { guard.check(); service.goBack() }
            DeviceAction.Home -> { guard.check(); service.goHome() }
            DeviceAction.Recents -> { guard.check(); service.openRecents() }
            DeviceAction.Notifications -> { guard.check(); service.openNotifications() }
            is DeviceAction.ClickNode, is DeviceAction.LongPressNode, is DeviceAction.SetText,
            is DeviceAction.ClearText, is DeviceAction.FocusNode, is DeviceAction.Scroll, is DeviceAction.ReadNode ->
                executeNode(action, state, guard)
            else -> false // Visual/coordinate/terminal actions never bypass native policy.
        }
        guard.check()
        DeviceDispatch(accepted, if (accepted) "Dispatched; native verification still required" else "Rejected")
    }

    @Suppress("DEPRECATION")
    private fun executeNode(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): Boolean {
        val original = state.snapshot.node(action.nodeRef() ?: return false) ?: return false
        var live = service.rootInActiveWindow ?: return false
        try {
            if (live.windowId != original.windowId) return false
            for (index in original.path.split('.').drop(1)) {
                val child = live.getChild(index.toIntOrNull() ?: return false) ?: return false
                live.recycle(); live = child
            }
            if (!live.refresh() || !live.isVisibleToUser) return false
            val fresh = describe(live, original.path, state.snapshot.displayBounds) ?: return false
            if (fresh.signature() != original.signature()) return false
            guard.validate(action, state, SystemClock.elapsedRealtime())
            if (!AgentRuntimeGate.isEnabled() || service.eventSequence != state.snapshot.eventSequence) return false
            return when (action) {
                is DeviceAction.ClickNode -> live.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                is DeviceAction.LongPressNode -> live.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                is DeviceAction.FocusNode -> live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                is DeviceAction.Scroll -> live.performAction(if (action.direction == ScrollDirection.FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                is DeviceAction.SetText, is DeviceAction.ClearText -> live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (action is DeviceAction.SetText) action.text else "")
                })
                is DeviceAction.ReadNode -> !fresh.password
                else -> false
            }
        } finally { live.recycle() }
    }

    override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) {
        val end = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(0, 2000)
        var sequence = service.eventSequence
        var quietSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < end) {
            check(AgentRuntimeGate.isEnabled())
            delay(50)
            check(AgentRuntimeGate.isEnabled())
            val current = service.eventSequence
            if (current != sequence) { sequence = current; quietSince = SystemClock.elapsedRealtime() }
            if (sequence > afterEventSequence && SystemClock.elapsedRealtime() - quietSince >= 150) return
        }
    }
}
