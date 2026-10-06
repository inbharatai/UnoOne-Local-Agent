package com.unoone.agent.overlay

/** Metadata only. No event package or context package participates in source authority. */
class ForegroundEvidenceReceipt private constructor(
    val packageName: String, val windowId: Int, val eventSequence: Long,
    val capturedAtElapsedMs: Long, val stopGeneration: Long
) {
    fun isFresh(now: Long, generation: Long): Boolean = generation == stopGeneration &&
        now >= capturedAtElapsedMs && now - capturedAtElapsedMs <= MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 15_000L
        fun capture(source: Source): ForegroundEvidenceReceipt? {
            val generation = source.generation()
            val sequence = source.sequence()
            val before = source.activeApplication()
            val root = source.activeRoot()
            val after = source.activeApplication()
            if (root == null || root.packageName.isBlank() || root.windowId < 0 ||
                before != root || after != root || sequence != source.sequence() ||
                generation != source.generation()) return null
            return ForegroundEvidenceReceipt(root.packageName, root.windowId, sequence, source.now(), generation)
        }
    }
    data class Identity(val packageName: String, val windowId: Int)
    interface Source {
        fun generation(): Long
        fun sequence(): Long
        fun now(): Long
        /** Must return only an active TYPE_APPLICATION window's own root identity. */
        fun activeApplication(): Identity?
        fun activeRoot(): Identity?
    }
}
