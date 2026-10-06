package com.unoone.agent.voice.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import com.unoone.agent.voice.processMicrophoneLease
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import java.io.ByteArrayOutputStream
import android.media.AudioManager

class AudioRecorder(private val requestEchoCancellation: Boolean = false) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AMPLITUDE_INTERVAL_MS = 100L
    }

    private var leaseToken: Any? = null
    @Volatile private var destructionAck = false
    private var audioRecord: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    fun isEchoCancellationAvailable(): Boolean = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
    /** OS effect status only; physical echo suppression is device-dependent and unverified. */
    fun isEchoCancellationEnabled(): Boolean = isRecording &&
        runCatching { echoCanceler?.enabled == true }.getOrDefault(false)
    private fun releaseEchoCanceler() {
        val effect = echoCanceler
        echoCanceler = null
        runCatching { effect?.release() }
    }
    private var recordingThread: Thread? = null
    private val bufferLock = Object()
    // 0C-6: ByteArrayOutputStream avoids boxing every byte into Byte objects
    // (was mutableListOf<Byte>() creating 32K objects/sec). Pre-allocate 64KB.
    private val audioBuffer = ByteArrayOutputStream(65536)
    @Volatile
    private var isRecording = false

    @Volatile private var captureContext: Context? = null
    @Volatile private var discarded = false

    private fun captureAllowed(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION && hasPermission(context)
    }

    var onAmplitude: ((Float) -> Unit)? = null

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun start(context: Context): Result<Unit> {
        if (!captureAllowed(context)) return Result.Error("Microphone unavailable during call or without permission")
        if (isRecording) return Result.Success(Unit)
        if (recordingThread?.isAlive == true) return Result.Error("Previous capture is still stopping")
        captureContext = context.applicationContext
        discarded = false
        if (!hasPermission(context)) {
            return Result.Error("Microphone permission not granted")
        }

        var removeModeListener: (() -> Unit)? = null
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                return Result.Error("Invalid audio buffer size")
            }

            val token = Any()
            if (!processMicrophoneLease.acquire(token)) return Result.Error("Microphone already owned by voice capture")
            leaseToken = token
            destructionAck = false
            val record = AudioRecord(
                if (requestEchoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                destructionAck = runCatching { record.release() }.isSuccess
                processMicrophoneLease.release(token, destructionAck, true)
                return Result.Error("AudioRecord failed to initialize")
            }

            audioRecord = record
            if (requestEchoCancellation && isEchoCancellationAvailable()) {
                echoCanceler = runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()
                runCatching { echoCanceler?.enabled = true }
            }
            audioBuffer.reset()
            isRecording = true
            val manager = context.getSystemService(AudioManager::class.java)
            val modeListener = if (android.os.Build.VERSION.SDK_INT >= 31) {
                AudioManager.OnModeChangedListener { mode ->
                    if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                        discarded = true
                        isRecording = false
                        synchronized(bufferLock) { audioBuffer.reset() }
                        runCatching { record.stop() }
                    }
                }.also { listener ->
                    removeModeListener = { manager?.removeOnModeChangedListener(listener); Unit }
                    manager?.addOnModeChangedListener(context.mainExecutor, listener)
                }
            } else null
            record.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                var lastAmplitudeTime = 0L
                try {
                while (isRecording) {
                    if (!captureAllowed(context)) { discarded = true; break }
                    val read = record.read(buffer, 0, buffer.size)
                    if (!captureAllowed(context)) { discarded = true; break }
                    if (read < 0) { if (isRecording) discarded = true; break }
                    if (read > 0 && isRecording) {
                        synchronized(bufferLock) {
                            if (!discarded && isRecording) audioBuffer.write(buffer, 0, read)
                        }

                        // Report amplitude for waveform visualization
                        val now = System.currentTimeMillis()
                        if (onAmplitude != null && now - lastAmplitudeTime >= AMPLITUDE_INTERVAL_MS) {
                            onAmplitude?.invoke(PcmRms.amplitude(buffer, read))
                            lastAmplitudeTime = now
                        }
                    }
                }
                } catch (e: Exception) {
                    discarded = true
                    Logger.e("Recording interrupted", e)
                } finally {
                    isRecording = false
                    if (discarded) synchronized(bufferLock) { audioBuffer.reset() }
                    runCatching { record.stop() }
                    releaseEchoCanceler()
                    destructionAck = runCatching { record.release() }.isSuccess
                    // Only stop(), after joining this worker, can relinquish the lease.
                    if (android.os.Build.VERSION.SDK_INT >= 31 && modeListener != null) {
                        runCatching { manager?.removeOnModeChangedListener(modeListener) }
                    }
                }
            }.apply {
                name = "UnoOne-Recorder-Thread"
                start()
            }

            Logger.i("AudioRecorder started")
            Result.Success(Unit)
        } catch (e: SecurityException) {
            runCatching { removeModeListener?.invoke() }
            Logger.e("Microphone permission was revoked while starting recording", e)
            isRecording = false
            releaseEchoCanceler()
            destructionAck = runCatching { audioRecord?.release() }.isSuccess
            leaseToken?.let { processMicrophoneLease.release(it, destructionAck, true) }
            audioRecord = null
            Result.Error("Microphone permission not granted", e)
        } catch (e: Exception) {
            runCatching { removeModeListener?.invoke() }
            Logger.e("Failed to start recording", e)
            // Reset half-initialized state so a subsequent start() doesn't silently no-op
            // (isRecording was set true before startRecording() could throw).
            isRecording = false
            releaseEchoCanceler()
            destructionAck = runCatching { audioRecord?.release() }.isSuccess
            leaseToken?.let { processMicrophoneLease.release(it, destructionAck, true) }
            audioRecord = null
            Result.Error("Failed to start recording: ${e.message}", e)
        }
    }

    @Synchronized
    fun stop(): ByteArray {
        if (captureContext?.let { captureAllowed(it) } != true) discarded = true
        isRecording = false
        onAmplitude?.invoke(0f)
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Logger.e("Error stopping recorder", e)
        }
        audioRecord = null
        // 0C-6: Increase thread join timeout from 500ms to 2000ms to prevent
        // truncated audio if the recording thread is still flushing data
        recordingThread?.join(2000)
        if (recordingThread?.isAlive == true) discarded = true
        else {
            recordingThread = null
            leaseToken?.let { if (processMicrophoneLease.release(it, destructionAck, true)) leaseToken = null }
        }

        val result = synchronized(bufferLock) {
            val bytes = if (discarded || captureContext?.let { captureAllowed(it) } != true) ByteArray(0) else audioBuffer.toByteArray()
            audioBuffer.reset()
            bytes
        }
        Logger.i("AudioRecorder stopped. Captured ${result.size} bytes")
        return result
    }

    fun isDrainAcknowledged(): Boolean = recordingThread?.isAlive != true && leaseToken == null

    fun isRecording(): Boolean = isRecording

    /**
     * 0C-7: Read accumulated audio data incrementally without stopping the recorder.
     * Returns the bytes accumulated since the last call to readChunk() (or since start).
     * This avoids the create/destroy AudioRecord cycle that VoiceService was doing every second.
     */
    fun readChunk(): ByteArray {
        synchronized(bufferLock) {
            val bytes = if (discarded || captureContext?.let { captureAllowed(it) } != true) ByteArray(0) else audioBuffer.toByteArray()
            audioBuffer.reset()
            return bytes
        }
    }
}
