package com.unoone.agent.voice

import java.util.concurrent.atomic.AtomicReference

/** Process-wide PCM + opt-in SpeechRecognizer admission. ACK means API destruction and
 * worker/child completion, NOT a measurement of the physical microphone or audio HAL. */
internal class MicrophoneLease {
    private val owner = AtomicReference<Any?>(null)
    fun acquire(token: Any): Boolean = owner.compareAndSet(null, token)
    fun release(token: Any, destructionAcknowledged: Boolean, childJoined: Boolean): Boolean =
        destructionAcknowledged && childJoined && owner.compareAndSet(token, null)
    fun isIdle(): Boolean = owner.get() == null
}
internal val processMicrophoneLease = MicrophoneLease()
