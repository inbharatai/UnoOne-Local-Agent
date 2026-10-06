package com.unoone.agent.overlay

import android.os.Looper
import com.unoone.agent.core.overlay.OwnOverlayBroker
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Registered app-owned windows only. Native window proof remains mandatory at every caller. */
object OwnOverlayBridge {
    private val generations = AtomicLong()
    @Volatile private var broker: OwnOverlayBroker? = null
    private var captureIdle: () -> Boolean = { true }
    data class Receipt internal constructor(internal val broker: OwnOverlayBroker, internal val token: OwnOverlayBroker.Receipt)
    fun install(port: OwnOverlayBroker.WindowPort, stopAvailable: () -> Boolean = { false }, captureIdle: () -> Boolean = { true }): OwnOverlayBroker {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(broker == null) { "Previous overlay owner still registered" }
        this.captureIdle = captureIdle
        return OwnOverlayBroker(port, generations.incrementAndGet(), stopAvailable).also { registeredViews.install(it); broker = it }
    }
    fun detach(owner: OwnOverlayBroker) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (broker !== owner) return
        try { owner.destroy() } finally { broker = null; registeredViews.clear(owner) }
    }
    private var reconciledStop = GlobalTaskCancellation.generation
    fun stop(owner: OwnOverlayBroker, masterEnabled: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (broker !== owner || reconciledStop == GlobalTaskCancellation.generation) return
        reconciledStop = GlobalTaskCancellation.generation
        owner.stop(masterEnabled)
    }
    private val registeredViews = OwnedViewRegistry<OwnOverlayBroker, android.view.View>()
    internal fun registerView(owner: OwnOverlayBroker, key: String, view: android.view.View) {
        check(Looper.myLooper() == Looper.getMainLooper())
        registeredViews.register(owner, key, view)
    }
    internal fun unregisterView(owner: OwnOverlayBroker, key: String, view: android.view.View) {
        check(Looper.myLooper() == Looper.getMainLooper())
        registeredViews.unregister(owner, key, view)
    }
    internal data class OwnedWindows(val owner: OwnOverlayBroker, val views: Map<String, android.view.View>,
                                     val ids: Map<String, Int>)
    /** Metadata only: this receipt is never a pixel-policy exemption. */
    internal fun attachedOwnWindows(): OwnedWindows? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val owner = broker ?: return null
        val views = registeredViews.snapshot(owner) ?: return null
        val chat = views["chat"] ?: return null
        if (!chat.isAttachedToWindow || !chat.isShown || chat.windowVisibility != android.view.View.VISIBLE) return null
        val attached = views.filterValues { it.isAttachedToWindow }
        val ids = linkedMapOf<String, Int>()
        for ((key, view) in attached) {
            val info = view.createAccessibilityNodeInfo() ?: return null
            val id = try { info.windowId } finally { @Suppress("DEPRECATION") info.recycle() }
            if (id < 0 || id in ids.values) return null
            ids[key] = id
        }
        return OwnedWindows(owner, attached, ids)
    }
    fun hasOwner(): Boolean = broker != null
    suspend fun acquireHidden(owner: String, stopGeneration: Long): Receipt {
        var acquired: Receipt? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                check(stopGeneration == GlobalTaskCancellation.generation) { "Stopped" }
                val b = checkNotNull(broker) { "No registered overlay owner" }
                // Temporary hide is not close: reject before any WindowPort.remove, preserve review/task.
                check(captureIdle()) { "Capture must finish draining before temporary overlay hide" }
                stop(b, com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled())
                Receipt(b, b.acquire(owner, stopGeneration)).also {
                    acquired = it
                    check(isCurrent(it)) { "Stale hide ACK" }
                }
            }
        } catch (t: Throwable) {
            // Handles prompt cancellation dropping a successfully produced Main ACK.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                acquired?.let { if (isCurrent(it)) it.broker.release(it.token) }
            }
            throw t
        }
    }
    fun isCurrent(receipt: Receipt): Boolean = broker === receipt.broker &&
        receipt.token.stopGeneration == GlobalTaskCancellation.generation && receipt.broker.current(receipt.token)
    suspend fun release(receipt: Receipt) = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
        if (isCurrent(receipt)) {
            if (!com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled()) receipt.broker.stop(false)
            else receipt.broker.release(receipt.token)
        }
    }
}

/** Frozen pre-chat source identity; never screen text. RouteApp must still bind fresh native evidence. */
object FloatingContextEvidence {
    @Volatile var current: ForegroundEvidenceReceipt? = null
        internal set

    /** Public native window/root metadata; includes every window, never a package-filtered graph. */
    private fun describeWindow(w: android.view.accessibility.AccessibilityWindowInfo,
                               metrics: android.util.DisplayMetrics): UnderlayScope.Window {
        val bounds = android.graphics.Rect(); w.getBoundsInScreen(bounds)
        val root = w.root
        return try {
            UnderlayScope.Window(w.id,
                w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, w.layer,
                system = w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM,
                rootPackage = root?.packageName?.toString(), rootWindowId = root?.windowId,
                active = w.isActive, focused = w.isFocused, title = w.title?.toString(),
                bounds = UnderlayScope.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                screenWidth = metrics.widthPixels, screenHeight = metrics.heightPixels)
        } finally { @Suppress("DEPRECATION") root?.recycle() }
    }

    /** Stale cached scope is unavailable, never replaced while chat is present. */
    fun admitted(now: Long, generation: Long): ForegroundEvidenceReceipt? =
        current?.takeIf { it.isFresh(now, generation) }

    /** New capture only: never updates cached receipt or any admitted/review purpose. */
    fun captureForNewChat(): ForegroundEvidenceReceipt? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val owned = OwnOverlayBridge.attachedOwnWindows() ?: return null
        val service = com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance() ?: return null
        fun soleUnderlay(): ForegroundEvidenceReceipt.Identity? {
            if (OwnOverlayBridge.attachedOwnWindows() != owned) return null
            val windows = service.windows
            return try {
                fun bounded(w: android.view.accessibility.AccessibilityWindowInfo): Boolean {
                    val r = android.graphics.Rect(); w.getBoundsInScreen(r); return !r.isEmpty
                }
                if (owned.ids.values.any { id -> windows.singleOrNull { it.id == id }?.let { bounded(it) } != true }) return null
                // Only proven structural OS bars are harmless SOURCE metadata; all other windows remain ambiguity.
                val appId = UnderlayScope.soleApplication(owned.ids.values.toList(), owned.ids["chat"], windows.map {
                    describeWindow(it, service.resources.displayMetrics)
                }) ?: return null
                val app = windows.singleOrNull { it.id == appId } ?: return null
                if (!bounded(app) || OwnOverlayBridge.attachedOwnWindows() != owned) return null
                val root = app.root ?: return null
                try {
                    if (root.windowId != app.id) null else root.packageName?.toString()?.let {
                        ForegroundEvidenceReceipt.Identity(it, root.windowId)
                    }
                } finally { @Suppress("DEPRECATION") root.recycle() }
            } finally { windows.forEach { @Suppress("DEPRECATION") it.recycle() } }
        }
        return runCatching {
            ForegroundEvidenceReceipt.capture(object : ForegroundEvidenceReceipt.Source {
                override fun generation() = GlobalTaskCancellation.generation
                override fun sequence() = service.eventSequence
                override fun now() = android.os.SystemClock.elapsedRealtime()
                override fun activeApplication() = soleUnderlay()
                override fun activeRoot() = soleUnderlay()
            })
        }.getOrNull()
    }

    fun capture(): ForegroundEvidenceReceipt? {
        val service = com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService.getInstance() ?: return null
        return runCatching {
            ForegroundEvidenceReceipt.capture(object : ForegroundEvidenceReceipt.Source {
                override fun generation() = GlobalTaskCancellation.generation
                override fun sequence() = service.eventSequence
                override fun now() = android.os.SystemClock.elapsedRealtime()
                override fun activeRoot(): ForegroundEvidenceReceipt.Identity? {
                    val root = service.rootInActiveWindow ?: return null
                    return try { root.packageName?.toString()?.let { ForegroundEvidenceReceipt.Identity(it, root.windowId) } }
                    finally { @Suppress("DEPRECATION") root.recycle() }
                }
                override fun activeApplication(): ForegroundEvidenceReceipt.Identity? {
                    val windows = service.windows
                    return try {
                        // Metadata-only structural bars do not discard any foreign app/overlay from the graph.
                        val appId = UnderlayScope.soleUnobscuredApplication(windows.map {
                            describeWindow(it, service.resources.displayMetrics)
                        }) ?: return null
                        val window = windows.singleOrNull { it.id == appId } ?: return null
                        if (!window.isActive || window.id < 0) return null
                        val root = window.root ?: return null
                        try {
                            if (root.windowId != window.id) null else root.packageName?.toString()?.let {
                                ForegroundEvidenceReceipt.Identity(it, root.windowId)
                            }
                        } finally { @Suppress("DEPRECATION") root.recycle() }
                    } finally { windows.forEach { @Suppress("DEPRECATION") it.recycle() } }
                }
            })
        }.getOrNull()
    }
}
