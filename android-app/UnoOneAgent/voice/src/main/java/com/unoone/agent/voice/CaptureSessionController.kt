package com.unoone.agent.voice

import com.unoone.agent.core.model.Result
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Production session lifecycle shared by VoiceModule and host tests. No Android or fake policy. */
class CaptureSessionController {
    open class Session {
        val revoked = AtomicBoolean(false)
        internal val operations = Mutex()
    }
    private val ownership = CaptureOwnership()
    val closed = AtomicBoolean(false)
    fun current(): Session? = ownership.current() as? Session
    fun acquire(session: Session, rollback: () -> Unit, start: () -> Result<Unit>): Result<Unit> =
        ownership.acquire(session, rollback) {
            if (closed.get()) Result.Error("Voice module is shut down") else start()
        }
    fun <T> matching(session: Session, action: () -> T): T? = ownership.matching(session, action)
    suspend fun <T> use(session: Session, revoked: () -> T, action: suspend () -> T): T =
        session.operations.withLock { if (session.revoked.get()) revoked() else action() }
    fun revoke(session: Session) { session.revoked.set(true) }
    /** Never joins or waits for native work on the caller. */
    fun shutdown(): Session? {
        closed.set(true)
        return current()?.also(::revoke)
    }
    /** Native callback must acknowledge API destruction AND worker/recognizer-child join. */
    suspend fun drain(session: Session, acknowledge: suspend () -> Boolean) = session.operations.withLock {
        // Worker-only start barrier: shutdown may observe the owner while native start is in flight.
        if (ownership.matching(session) { true } != true) return@withLock
        if (acknowledge()) ownership.release(session)
    }
}
