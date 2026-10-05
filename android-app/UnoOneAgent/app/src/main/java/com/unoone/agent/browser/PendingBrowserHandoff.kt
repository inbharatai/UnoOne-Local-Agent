package com.unoone.agent.browser

import java.net.URI

/** Native, one-shot handoff. Call from the UI thread with navigation/ready events. */
class PendingBrowserHandoff {
    data class Request(val generation: Long, val origin: String, val task: String)
    private var generation = 0L
    private var pending: Request? = null
    private var startedGeneration: Long? = null

    @Synchronized fun offer(origin: String, task: String, activeTask: Boolean): Request? {
        // Rejection must precede any state change or navigation by the caller.
        if (activeTask) return null
        val request = Request(++generation, origin, task)
        pending = request
        startedGeneration = null
        return request
    }

    @Synchronized fun revoke() {
        ++generation
        pending = null
        startedGeneration = null
    }

    @Synchronized fun navigationStarted(url: String): Long? {
        startedGeneration = pending?.takeIf { sameOrigin(it.origin, url) }?.generation
        return startedGeneration
    }

    @Synchronized fun consume(token: Long?, readyUrl: String, currentUrl: String,
                              controllerReady: Boolean, activeTask: Boolean): Request? {
        val request = pending ?: return null
        if (!controllerReady || activeTask || token != request.generation ||
            startedGeneration != token || !sameOrigin(request.origin, readyUrl) ||
            !sameOrigin(request.origin, currentUrl) || readyUrl != currentUrl) return null
        pending = null
        startedGeneration = null
        return request
    }

    private fun sameOrigin(a: String, b: String): Boolean {
        fun origin(value: String): Triple<String, String, Int>? = runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            Triple(scheme, host, if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80)
        }.getOrNull()
        val expected = origin(a) ?: return false
        return expected == origin(b)
    }
}
