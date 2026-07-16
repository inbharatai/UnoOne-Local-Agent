package com.unoone.agent.voice

/**
 * Bounded PCM buffer for one wake-word command utterance.
 *
 * At 16 kHz, mono, PCM16, [maxBytes] defaults to 15 seconds. Once full, callers should finish the
 * utterance rather than grow memory indefinitely. Chunks are copied into the final contiguous array
 * only once, when STT begins.
 */
internal class PcmChunkAccumulator(
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) {
    private val chunks = ArrayList<ByteArray>()
    var size: Int = 0
        private set

    val isFull: Boolean get() = size >= maxBytes

    /** Adds as much of [chunk] as fits and returns the accepted byte count. */
    fun add(chunk: ByteArray): Int {
        if (chunk.isEmpty() || isFull) return 0
        val accepted = minOf(chunk.size, maxBytes - size)
        chunks += if (accepted == chunk.size) chunk.copyOf() else chunk.copyOf(accepted)
        size += accepted
        return accepted
    }

    fun clear() {
        chunks.clear()
        size = 0
    }

    fun toByteArray(): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
        }
        return output
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_COMMAND_SECONDS = 15
        const val DEFAULT_MAX_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_COMMAND_SECONDS
    }
}
