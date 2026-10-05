package com.unoone.agent.voice.recorder

import kotlin.math.sqrt

/** Signed little-endian PCM16; an incomplete trailing sample is ignored. */
internal object PcmRms {
    fun amplitude(bytes: ByteArray, length: Int = bytes.size): Float {
        require(length in 0..bytes.size)
        val count = length / 2
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val sample = ((bytes[i * 2].toInt() and 255) or
                ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toShort().toDouble()
            sum += sample * sample
        }
        return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
