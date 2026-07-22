package com.unoone.agent.phonecontrol

/**
 * Confirms detector labels from several recent observations without requiring perfectly
 * consecutive frames. Real-time detectors commonly miss a valid object for one analyzed frame;
 * clearing all evidence on that miss made Blind Aid draw boxes but never speak their names.
 */
internal class ObjectLabelEvidence(
    private val minimumHits: Int = 3,
    private val windowMs: Long = 1_500L
) {
    private val sightings = mutableMapOf<String, ArrayDeque<Long>>()

    fun update(nowMs: Long, currentLabels: Set<String>): Set<String> {
        val cutoff = nowMs - windowMs
        currentLabels.forEach { label ->
            sightings.getOrPut(label) { ArrayDeque() }.addLast(nowMs)
        }

        val iterator = sightings.iterator()
        while (iterator.hasNext()) {
            val (_, times) = iterator.next()
            while (times.isNotEmpty() && times.first() < cutoff) times.removeFirst()
            if (times.isEmpty()) iterator.remove()
        }

        // A recently vanished object is never narrated from history alone. The rolling window is
        // only used to tolerate intermittent detector misses while the label is present now.
        return currentLabels.filterTo(linkedSetOf()) { label ->
            (sightings[label]?.size ?: 0) >= minimumHits
        }
    }

    fun clear() = sightings.clear()
}
