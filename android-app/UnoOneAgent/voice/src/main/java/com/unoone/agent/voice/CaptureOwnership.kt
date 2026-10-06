package com.unoone.agent.voice

import com.unoone.agent.core.model.Result

/** Admission and native start/stop share a monitor: a stale owner's cleanup cannot stop a successor. */
internal class CaptureOwnership {
    private var owner: Any? = null

    @Synchronized
    fun acquire(token: Any, rollback: () -> Unit = {}, start: () -> Result<Unit>): Result<Unit> {
        if (owner === token) return Result.Success(Unit)
        if (owner != null) return Result.Error("Microphone already owned by another capture")
        owner = token
        return try {
            start().also { if (it is Result.Error) owner = null }
        } catch (e: Throwable) {
            try { rollback() } finally { owner = null }
            throw e
        }
    }

    @Synchronized
    fun <T> matching(token: Any, action: () -> T): T? = if (owner === token) action() else null

    @Synchronized
    fun release(token: Any) { if (owner === token) owner = null }

    @Synchronized
    fun <T> stopAll(stop: () -> T): T = try { stop() } finally { owner = null }
}
