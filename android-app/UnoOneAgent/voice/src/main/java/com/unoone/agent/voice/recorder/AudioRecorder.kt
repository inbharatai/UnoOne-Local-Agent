package com.unoone.agent.voice.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AMPLITUDE_INTERVAL_MS = 100L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val bufferLock = Object()
    // 0C-6: ByteArrayOutputStream avoids boxing every byte into Byte objects
    // (was mutableListOf<Byte>() creating 32K objects/sec). Pre-allocate 64KB.
    private val audioBuffer = ByteArrayOutputStream(65536)
    @Volatile
    private var isRecording = false

    var onAmplitude: ((Float) -> Unit)? = null

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun start(context: Context): Result<Unit> {
        if (isRecording) return Result.Success(Unit)
        if (!hasPermission(context)) {
            return Result.Error("Microphone permission not granted")
        }

        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                return Result.Error("Invalid audio buffer size")
            }

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() } // don't leak the native AudioRecord on init failure
                return Result.Error("AudioRecord failed to initialize")
            }

            audioRecord = record
            audioBuffer.reset()
            isRecording = true
            record.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                var lastAmplitudeTime = 0L
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        synchronized(bufferLock) {
                            audioBuffer.write(buffer, 0, read)
                        }

                        // Report amplitude for waveform visualization
                        val now = System.currentTimeMillis()
                        if (onAmplitude != null && now - lastAmplitudeTime >= AMPLITUDE_INTERVAL_MS) {
                            var sum = 0.0
                            for (i in 0 until read step 2) {
                                // Little-endian signed 16-bit: mask the high byte to avoid sign-extension.
                                val sample = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
                                sum += sample.toDouble() * sample.toDouble()
                            }
                            val rms = sqrt(sum / (read / 2)).toFloat() / 32768f
                            onAmplitude?.invoke(rms.coerceIn(0f, 1f))
                            lastAmplitudeTime = now
                        }
                    }
                }
            }.apply {
                name = "UnoOne-Recorder-Thread"
                start()
            }

            Logger.i("AudioRecorder started")
            Result.Success(Unit)
        } catch (e: SecurityException) {
            Logger.e("Microphone permission was revoked while starting recording", e)
            isRecording = false
            runCatching { audioRecord?.release() }
            audioRecord = null
            Result.Error("Microphone permission not granted", e)
        } catch (e: Exception) {
            Logger.e("Failed to start recording", e)
            // Reset half-initialized state so a subsequent start() doesn't silently no-op
            // (isRecording was set true before startRecording() could throw).
            isRecording = false
            runCatching { audioRecord?.release() }
            audioRecord = null
            Result.Error("Failed to start recording: ${e.message}", e)
        }
    }

    fun stop(): ByteArray {
        if (!isRecording) return ByteArray(0)
        isRecording = false
        onAmplitude?.invoke(0f)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Logger.e("Error stopping recorder", e)
        }
        audioRecord = null
        // 0C-6: Increase thread join timeout from 500ms to 2000ms to prevent
        // truncated audio if the recording thread is still flushing data
        recordingThread?.join(2000)
        recordingThread = null

        val result = synchronized(bufferLock) {
            val bytes = audioBuffer.toByteArray()
            audioBuffer.reset()
            bytes
        }
        Logger.i("AudioRecorder stopped. Captured ${result.size} bytes")
        return result
    }

    fun isRecording(): Boolean = isRecording

    /**
     * 0C-7: Read accumulated audio data incrementally without stopping the recorder.
     * Returns the bytes accumulated since the last call to readChunk() (or since start).
     * This avoids the create/destroy AudioRecord cycle that VoiceService was doing every second.
     */
    fun readChunk(): ByteArray {
        synchronized(bufferLock) {
            val bytes = audioBuffer.toByteArray()
            audioBuffer.reset()
            return bytes
        }
    }
}
