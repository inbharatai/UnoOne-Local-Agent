package com.unoone.agent.voice

import com.unoone.agent.core.model.Result
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Admission and native start/stop share a monitor: a stale owner's cleanup cannot stop a successor. */
internal class CaptureOwnership {
    @Volatile private var owner: Any? = null
    private val lock = ReentrantLock()
    fun current(): Any? = owner

    fun acquire(token: Any, rollback: () -> Unit = {}, start: () -> Result<Unit>): Result<Unit> {
        if (!lock.tryLock()) return Result.Error("Microphone is draining; retry")
        try {
        if (owner != null) return Result.Error("Microphone already owned by another capture")
        owner = token
        return try {
            start().also { if (it is Result.Error) owner = null }
        } catch (e: Throwable) {
            try { rollback() } finally { owner = null }
            throw e
        }
        } finally { lock.unlock() }
    }

    fun <T> matching(token: Any, action: () -> T): T? = lock.withLock { if (owner === token) action() else null }

    fun release(token: Any) = lock.withLock { if (owner === token) owner = null }

    fun <T> stopAll(stop: () -> T): T = lock.withLock { try { stop() } finally { owner = null } }
}
