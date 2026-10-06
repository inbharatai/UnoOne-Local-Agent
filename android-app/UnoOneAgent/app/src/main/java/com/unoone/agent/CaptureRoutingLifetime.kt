package com.unoone.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Capture retirement is not task cancellation. Confined to the surface's main dispatcher. */
class CaptureRoutingLifetime(private val scope: CoroutineScope) {
    private val routes = mutableSetOf<Job>()

    fun <T : Any> retire(owner: T, current: T?, release: () -> Unit): Boolean {
        if (owner !== current) return false
        release()
        return true
    }

    fun route(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        routes.add(job)
        job.invokeOnCompletion { routes.remove(job) }
        job.start()
        return job
    }

    fun stop() {
        val pending = routes.toList()
        routes.clear()
        pending.forEach { it.cancel() }
    }
}
