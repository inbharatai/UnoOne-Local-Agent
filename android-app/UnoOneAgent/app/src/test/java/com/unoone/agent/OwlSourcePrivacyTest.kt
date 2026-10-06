package com.unoone.agent

import com.unoone.agent.core.device.*
import com.unoone.agent.core.task.PixelWindowPolicy
import com.unoone.agent.owl.OwlSourcePrivacy
import org.junit.Assert.*
import org.junit.Test

/** Synthetic native metadata only; NOT a real capture, model run, or native vision qualification. */
class OwlSourcePrivacyTest {
    private val display = RectData(0, 0, 100, 200)
    private fun snapshot(pkg: String = "demo", bounds: RectData = display, className: String = "android.widget.Button") = UiSnapshot("fixture", 1, 1, display,
        listOf(UiWindow(7, pkg, bounds, listOf(UiNode("button", 7, "0", pkg, className,
            text = "Search", bounds = RectData(0, 0, 50, 50), clickable = true, semantic = TargetSemantic.NAVIGATION)))))
    @Test fun wrongPackageAndNonFullFrameFailClosed() {
        assertTrue(OwlSourcePrivacy.admissible(snapshot(), "demo"))
        assertFalse(OwlSourcePrivacy.admissible(snapshot("other"), "demo"))
        assertFalse(OwlSourcePrivacy.admissible(snapshot(bounds = RectData(0, 0, 100, 100)), "demo"))
    }
    @Test fun imageBearingWidgetsFailClosedEvenWithNavigationSemantic() {
        listOf("android.widget.ImageView", "android.widget.ImageButton", "android.widget.VideoView", "android.view.TextureView").forEach {
            assertFalse(it, OwlSourcePrivacy.admissible(snapshot(className = it), "demo"))
        }
        assertTrue(OwlSourcePrivacy.admissible(snapshot(className = "android.widget.TextView"), "demo"))
    }
    @Test fun actualWindowInventoryRejectsOverlayEvenIfSnapshotLooksSafe() {
        var captures = 0
        val source = snapshot()
        if (OwlSourcePrivacy.admissible(source, "demo") && PixelWindowPolicy.allows(listOf(7 to 1, 8 to 4), 7, 1)) captures++
        assertEquals(0, captures)
    }
}
