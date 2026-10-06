package com.unoone.agent.core.task

import kotlinx.coroutines.CompletableDeferred

/** Registration admission and native-close ACKs; the monitor never encloses native work. */
class BlindAidProducerGate {
    class Producer internal constructor(val epoch: Long, internal val revoke: () -> Unit) {
        private val closed = CompletableDeferred<Unit>()
        fun acknowledgeClosed() { closed.complete(Unit) }
        internal suspend fun awaitClosed() = closed.await()
    }
    private var epoch = -1L
    private var admitting = false
    private val producers = mutableListOf<Producer>()

    @Synchronized fun open(expectedEpoch: Long): Boolean {
        if (epoch != expectedEpoch) return false
        check(producers.isEmpty())
        admitting = true
        return true
    }
    @Synchronized fun register(expectedEpoch: Long, revoke: () -> Unit): Producer? {
        if (!admitting || epoch != expectedEpoch) return null
        return Producer(expectedEpoch, revoke).also { producers.add(it) }
    }
    fun deactivate(nextEpoch: Long) {
        val snapshot = synchronized(this) {
            if (nextEpoch < epoch) return
            epoch = nextEpoch
            admitting = false
            producers.toList()
        }
        snapshot.forEach { it.revoke() }
    }
    suspend fun awaitAllClosed() {
        val snapshot = synchronized(this) { check(!admitting); producers.toList() }
        snapshot.forEach { it.awaitClosed() }
        synchronized(this) { producers.removeAll(snapshot.toSet()) }
    }
}
