package com.unoone.agent.overlay

/** No package or layer can turn an unregistered non-application window into our overlay. */
internal object UnderlayScope {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Window(val id: Int, val application: Boolean, val layer: Int,
                      val system: Boolean = false, val rootPackage: String? = null,
                      val rootWindowId: Int? = null, val active: Boolean = false,
                      val focused: Boolean = false, val title: String? = null,
                      val bounds: Bounds? = null, val screenWidth: Int = 0, val screenHeight: Int = 0)

    /** Source metadata ONLY: never use this predicate to authorize screenshot pixels. */
    fun structuralEdgeBar(w: Window): Boolean {
        if (!w.system || w.application || w.id < 0 || w.rootWindowId != w.id ||
            w.rootPackage != "com.android.systemui" || w.active || w.focused) return false
        val b = w.bounds ?: return false
        val width = w.screenWidth; val height = w.screenHeight
        if (width <= 0 || height <= 0 || b.left < 0 || b.top < 0 ||
            b.right > width || b.bottom > height || b.right <= b.left || b.bottom <= b.top) return false
        val maxThickness = minOf(width, height) / 10
        val horizontal = b.left == 0 && b.right == width && b.bottom - b.top <= maxThickness
        val vertical = b.top == 0 && b.bottom == height && b.right - b.left <= maxThickness
        return when (w.title) {
            "StatusBar" -> horizontal && b.top == 0
            "NavigationBar" -> (horizontal && b.bottom == height) ||
                (vertical && (b.left == 0 || b.right == width))
            else -> false
        }
    }

    fun soleUnobscuredApplication(windows: List<Window>): Int? {
        if (windows.any { it.id < 0 } || windows.map { it.id }.distinct().size != windows.size) return null
        if (windows.any { !it.application && !structuralEdgeBar(it) }) return null
        return windows.singleOrNull { it.application }?.id
    }
    fun soleApplication(ownIds: List<Int?>, chatId: Int?, windows: List<Window>): Int? {
        if (chatId == null || chatId < 0 || ownIds.isEmpty() || ownIds.any { it == null || it < 0 }) return null
        if (ownIds.distinct().size != ownIds.size || chatId !in ownIds) return null
        if (windows.any { it.id < 0 } || windows.map { it.id }.distinct().size != windows.size) return null
        if (ownIds.any { id -> windows.singleOrNull { it.id == id }?.application != false }) return null
        if (windows.any { !it.application && it.id !in ownIds && !structuralEdgeBar(it) }) return null
        val app = windows.singleOrNull { it.application } ?: return null
        val chat = windows.single { it.id == chatId }
        return app.id.takeIf { app.layer < chat.layer }
    }
    fun soleApplication(ownId: Int?, windows: List<Window>): Int? =
        soleApplication(listOf(ownId), ownId, windows)

}

internal object NativeVoiceWindowCheck {
    fun matches(explicitOpen: Boolean, expectedPackage: String?, expectedId: Int?,
                actualPackage: String?, actualId: Int?): Boolean = explicitOpen ||
        (expectedPackage != null && expectedPackage.isNotBlank() && expectedId != null && expectedId >= 0 &&
            actualPackage == expectedPackage && actualId == expectedId)
}

/** Main-thread caller supplies the session owner; reference identity, never package identity. */
internal class OwnedViewRegistry<O : Any, V : Any> {
    private var owner: O? = null
    private val views = linkedMapOf<String, V>()
    fun install(session: O) { check(owner == null); owner = session }
    fun register(session: O, key: String, view: V) {
        check(owner === session) { "Stale overlay owner" }
        check(views[key] == null || views[key] === view) { "Stale view replacement" }
        check(views.none { (k, v) -> k != key && v === view }) { "Duplicate owned view" }
        views[key] = view
    }
    fun unregister(session: O, key: String, view: V) {
        if (owner === session && views[key] === view) views.remove(key)
    }
    fun clear(session: O) { if (owner === session) { views.clear(); owner = null } }
    fun snapshot(session: O): Map<String, V>? = if (owner === session) views.toMap() else null
}
