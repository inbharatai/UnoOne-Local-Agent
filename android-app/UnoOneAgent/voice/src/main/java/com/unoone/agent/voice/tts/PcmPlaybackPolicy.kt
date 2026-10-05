package com.unoone.agent.voice.tts

import com.unoone.agent.core.model.Result
import kotlinx.coroutines.delay

/** Duration-based completion estimate, not proof that a speaker produced audible sound. */
internal suspend fun awaitPcmPlayback(
    timeoutMs: Long,
    isCurrent: () -> Boolean,
    finish: () -> Unit,
    start: suspend () -> Pair<Result<Unit>, Long>
): Result<Unit> = try {
    val (result, playbackMs) = start()
    when {
        result is Result.Error -> result
        !isCurrent() -> Result.Error("Speech interrupted")
        else -> {
            val duration = playbackMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE - 100L) + 100L
            delay(minOf(duration, timeoutMs.coerceAtLeast(0L)))
            when {
                !isCurrent() -> Result.Error("Speech interrupted")
                duration > timeoutMs -> Result.Error("Speech playback timed out before completion")
                else -> Result.Success(Unit)
            }
        }
    }
} finally {
    finish()
}
