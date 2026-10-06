package com.unoone.agent.core.task

/** Full-display OCR cannot attribute pixels to packages if any other window is present. */
object PixelWindowPolicy {
    fun allows(windows: List<Pair<Int, Int>>, activeWindowId: Int, applicationWindowType: Int): Boolean =
        windows.size == 1 && windows.single().first == activeWindowId && windows.single().second == applicationWindowType
}
