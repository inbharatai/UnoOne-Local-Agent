package com.unoone.agent.core.task

/** Native request-scoped identity. Never expands the captured grant from subsequent observations. */
class NativeReadBoundary<T>(packages: Set<String>, private val identity: () -> Pair<String, T>,
    private val checkActive: () -> Unit) {
    private val admitted = packages.toSet()
    private val initial = identity().also { require(it.first in admitted) { "Screen outside admitted package scope" } }
    fun verify() {
        checkActive()
        check(identity() == initial) { "Screen changed during read" }
    }
    suspend fun <R> read(block: suspend (() -> Unit) -> R): R {
        verify()
        return block(::verify).also { verify() }
    }
}

/** Pure acceptance predicate shared by producer callbacks and host Stop regressions. */
fun acceptsBlindAidFeedback(active: Boolean, epoch: Long, currentEpoch: Long, enabled: Boolean): Boolean =
    active && epoch == currentEpoch && enabled
