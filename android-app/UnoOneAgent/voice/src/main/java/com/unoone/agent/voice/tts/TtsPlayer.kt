package com.unoone.agent.voice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger

class TtsPlayer {

    private var audioTrack: AudioTrack? = null

    fun playPcm(samples: FloatArray, sampleRate: Int = 22050): Result<Unit> {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .build()

            audioTrack = track
            track.play()

            // Convert FloatArray to ByteArray (PCM 16-bit)
            val bytes = samples.map { (it * 32767).toInt().toShort() }
                .flatMap { listOf(it.toByte(), (it.toInt() shr 8).toByte()) }
                .toByteArray()

            track.write(bytes, 0, bytes.size)
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e("TTS playback failed", e)
            Result.Error("Playback failed: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error stopping TTS playback", e)
        }
        audioTrack = null
    }
}
