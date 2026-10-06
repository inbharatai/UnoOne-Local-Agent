package com.unoone.agent

import com.unoone.agent.overlay.OwnedViewRegistry
import com.unoone.agent.overlay.UnderlayScope
import com.unoone.agent.overlay.NativeVoiceWindowCheck
import org.junit.Assert.*
import org.junit.Test

class UnderlayScopeTest {
    private val app = UnderlayScope.Window(1, true, 0)
    private val own = UnderlayScope.Window(2, false, 10)
    private fun bar() = UnderlayScope.Window(8, false, 30, system = true,
        rootPackage = "com.android.systemui", rootWindowId = 8, title = "StatusBar",
        bounds = UnderlayScope.Bounds(0, 0, 1080, 80), screenWidth = 1080, screenHeight = 2400)
    @Test fun provenEdgeBarsAllowMetadataOnly() {
        val status = bar()
        val nav = status.copy(id = 9, rootWindowId = 9, title = "NavigationBar",
            bounds = UnderlayScope.Bounds(0, 2320, 1080, 2400))
        assertEquals(1, UnderlayScope.soleApplication(2, listOf(app, own, status, nav)))
        assertEquals(1, UnderlayScope.soleUnobscuredApplication(listOf(app, status, nav)))
    }
    @Test fun lookalikesExpandedAndUnprovenBarsReject() {
        val bar = bar()
        val denied = listOf(bar.copy(system = false), bar.copy(rootPackage = "foreign.app"),
            bar.copy(rootWindowId = 99), bar.copy(rootPackage = null), bar.copy(active = true),
            bar.copy(focused = true), bar.copy(title = "NotificationShade"), bar.copy(title = null),
            bar.copy(bounds = UnderlayScope.Bounds(0, 0, 1080, 2400)),
            bar.copy(bounds = UnderlayScope.Bounds(0, 0, 1080, 900)),
            bar.copy(bounds = UnderlayScope.Bounds(0, 100, 1080, 180)),
            bar.copy(application = true), bar.copy(screenWidth = 0))
        for (window in denied) for (layer in listOf(-10, 30)) {
            assertNull(UnderlayScope.soleApplication(2, listOf(app, own, window.copy(layer = layer))))
            assertNull(UnderlayScope.soleUnobscuredApplication(listOf(app, window.copy(layer = layer))))
        }
    }
    @Test fun bubbleAndChatAreValidMetadataOnly() {
        val bubble = UnderlayScope.Window(3, false, 5)
        assertEquals(1, UnderlayScope.soleApplication(listOf(2, 3), 2, listOf(app, own, bubble)))
        for (layer in listOf(-1, 5, 20)) {
            assertNull(UnderlayScope.soleApplication(listOf(2, 3), 2,
                listOf(app, own, bubble, UnderlayScope.Window(4, false, layer))))
        }
    }
    @Test fun duplicateUnknownOrMissingChatIdsDeny() {
        val windows = listOf(app, own)
        assertNull(UnderlayScope.soleApplication(listOf(2, 2), 2, windows))
        assertNull(UnderlayScope.soleApplication(listOf(2, null), 2, windows))
        assertNull(UnderlayScope.soleApplication(listOf(2, -1), 2, windows))
        assertNull(UnderlayScope.soleApplication(listOf(2, 99), 2, windows))
        assertNull(UnderlayScope.soleApplication(listOf(2), null, windows))
        assertNull(UnderlayScope.soleApplication(listOf(2), 2, windows + own))
    }
    @Test fun staleViewReplacementAndOldOwnerDeny() {
        val registry = OwnedViewRegistry<Any, Any>()
        val owner = Any(); val next = Any(); val first = Any(); val replacement = Any()
        registry.install(owner); registry.register(owner, "chat", first)
        val before = registry.snapshot(owner)
        assertTrue(runCatching { registry.register(owner, "chat", replacement) }.isFailure)
        registry.unregister(owner, "chat", first)
        registry.register(owner, "chat", replacement)
        assertNotEquals(before, registry.snapshot(owner))
        registry.unregister(owner, "chat", first) // stale removal cannot remove replacement
        assertSame(replacement, registry.snapshot(owner)!!["chat"])
        registry.clear(owner); registry.install(next)
        registry.register(next, "chat", replacement)
        registry.clear(owner) // stale destruction cannot clear current session
        assertSame(replacement, registry.snapshot(next)!!["chat"])
        assertTrue(runCatching { registry.register(owner, "bubble", Any()) }.isFailure)
        assertNull(registry.snapshot(owner))
    }
    @Test fun foreignAboveAndBelowBothReject() {
        for (layer in listOf(-1, 5, 20)) {
            assertNull(UnderlayScope.soleApplication(2, listOf(app, own, UnderlayScope.Window(3, false, layer))))
        }
    }
    @Test fun missingOrNegativeOwnIdRejects() {
        assertNull(UnderlayScope.soleApplication(null, listOf(app, own)))
        assertNull(UnderlayScope.soleApplication(-1, listOf(app, own)))
        assertNull(UnderlayScope.soleApplication(99, listOf(app, own)))
    }
    @Test fun exactSoleAppRequired() {
        assertEquals(1, UnderlayScope.soleApplication(2, listOf(app, own)))
        assertNull(UnderlayScope.soleApplication(2, listOf(app, own, UnderlayScope.Window(4, true, 1))))
    }
    @Test fun newInstanceOfSamePackageRejects() {
        assertFalse(NativeVoiceWindowCheck.matches(false, "test.app", 1, "test.app", 2))
        assertFalse(NativeVoiceWindowCheck.matches(false, "test.app", -1, "test.app", -1))
        assertTrue(NativeVoiceWindowCheck.matches(false, "test.app", 1, "test.app", 1))
    }
    @Test fun initialExplicitOpenAllowsNewDeclaredWindow() {
        assertTrue(NativeVoiceWindowCheck.matches(true, "test.app", 1, "test.app", 2))
    }
}
