package com.unoone.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.util.Logger
import com.unoone.agent.voice.recorder.AudioRecorder
import com.unoone.agent.voice.stt.AndroidSttEngine
import com.unoone.agent.voice.stt.KeywordSpotterEngine
import com.unoone.agent.voice.stt.SherpaSttEngine
import com.unoone.agent.voice.tts.SherpaTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VoiceService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var monitoringJob: Job? = null

    private val recorder = AudioRecorder()
    private var keywordSpotter: KeywordSpotterEngine? = null
    private var sttEngine: SherpaSttEngine? = null
    private var ttsEngine: SherpaTtsEngine? = null
    private var androidStt: AndroidSttEngine? = null
    private var useAndroidStt = true

    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandReceived: ((String) -> Unit)? = null

    @Volatile
    private var isListeningForCommand = false

    companion object {
        private const val CHANNEL_ID = "voice_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("UnoOne is listening"))
        Logger.i("VoiceService: Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initEngines()
        startMonitoring()
        return START_STICKY
    }

    private fun initEngines() {
        // Try Sherpa-ONNX STT
        val modelDir = getExternalFilesDir(null)?.absolutePath + "/models"
        val stt = SherpaSttEngine("$modelDir/sherpa-asr")
        if (stt.initialize() is Result.Success) {
            sttEngine = stt
            useAndroidStt = false
            Logger.i("VoiceService: Sherpa STT ready")
        } else {
            Logger.w("VoiceService: Sherpa STT unavailable, using Android fallback")
            useAndroidStt = true
        }

        // Try Sherpa-ONNX TTS
        val tts = SherpaTtsEngine("$modelDir/sherpa-tts")
        if (tts.initialize() is Result.Success) {
            ttsEngine = tts
            Logger.i("VoiceService: Sherpa TTS ready")
        } else {
            Logger.w("VoiceService: Sherpa TTS unavailable")
        }

        // Try Keyword Spotter
        val kws = KeywordSpotterEngine("$modelDir/vad")
        if (kws.initialize(listOf("uno one", "uno one")) is Result.Success) {
            keywordSpotter = kws
            Logger.i("VoiceService: Keyword spotter ready")
        } else {
            Logger.w("VoiceService: Keyword spotter unavailable, using continuous listen mode")
        }
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            try {
                if (keywordSpotter != null) {
                    startKeywordSpottingLoop()
                } else {
                    Logger.i("VoiceService: No keyword spotter, listening for manual activation only")
                    // Without a keyword spotter, the service stays alive for manual mic activation
                    // The FloatingAgentService or AgentScreen will trigger recording directly
                }
            } catch (e: Exception) {
                Logger.e("VoiceService: Monitoring failed", e)
            }
        }
    }

    private suspend fun startKeywordSpottingLoop() {
        Logger.i("VoiceService: Starting keyword spotting loop")
        val kws = keywordSpotter ?: return
        val chunkSizeMs = 1000L // Process 1-second chunks
        val chunkSamples = (AudioRecorder.SAMPLE_RATE * chunkSizeMs / 1000).toInt()
        val chunkBytes = chunkSamples * 2 // 16-bit = 2 bytes per sample

        var consecutiveSilenceChunks = 0
        val maxSilenceChunks = 3 // 3 seconds of silence = end of command

        while (serviceScope.isActive) {
            try {
                if (!recorder.hasPermission(this@VoiceService)) {
                    delay(1000)
                    continue
                }

                // Start recording for keyword spotting
                if (!recorder.isRecording()) {
                    recorder.start()
                }

                // Wait to accumulate audio
                delay(chunkSizeMs)

                // Get accumulated audio
                val pcmData = recorder.stop()
                if (pcmData.isEmpty()) {
                    delay(200)
                    continue
                }

                if (!isListeningForCommand) {
                    // Check for wake word
                    val keyword = kws.processChunk(pcmData)
                    if (keyword != null) {
                        Logger.i("VoiceService: Wake word detected: '$keyword'")
                        isListeningForCommand = true
                        consecutiveSilenceChunks = 0
                        onWakeWordDetected?.invoke()

                        // Update notification
                        updateNotification("Listening for command...")

                        // Start recording for command
                        recorder.start()
                    }
                } else {
                    // In command mode, check for silence
                    val hasSpeech = hasSpeechActivity(pcmData)

                    if (hasSpeech) {
                        consecutiveSilenceChunks = 0
                    } else {
                        consecutiveSilenceChunks++
                    }

                    // Accumulate command audio until silence detected
                    if (consecutiveSilenceChunks >= maxSilenceChunks) {
                        // End of command - transcribe
                        val commandPcm = recorder.stop()
                        isListeningForCommand = false

                        val transcript = transcribeAudio(commandPcm)
                        if (transcript is Result.Success && transcript.data.isNotBlank()) {
                            Logger.i("VoiceService: Command: '${transcript.data}'")
                            onCommandReceived?.invoke(transcript.data)
                        }

                        updateNotification("UnoOne is listening")
                        // Resume keyword spotting
                    }
                }
            } catch (e: Exception) {
                Logger.e("VoiceService: Error in spotting loop", e)
                delay(500)
            }
        }
    }

    private fun hasSpeechActivity(pcmData: ByteArray): Boolean {
        // Simple energy-based VAD: check if RMS exceeds threshold
        if (pcmData.size < 2) return false
        var sum = 0.0
        for (i in 0 until pcmData.size - 1 step 2) {
            val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / (pcmData.size / 2))
        return rms > 500 // Threshold for speech detection
    }

    private suspend fun transcribeAudio(pcmData: ByteArray): Result<String> {
        return if (!useAndroidStt && sttEngine != null) {
            sttEngine!!.transcribe(pcmData)
        } else {
            // Android STT handles its own recording, so we use it differently
            val engine = androidStt ?: AndroidSttEngine(this).also { androidStt = it }
            val initResult = engine.initialize()
            if (initResult is Result.Error) return initResult
            engine.transcribeOnce()
        }
    }

    private fun sqrt(x: Double): Double = kotlin.math.sqrt(x)

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "UnoOne Voice Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background listener for hands-free commands"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String = "Say 'UnoOne' to give a command"): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UnoOne")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        recorder.stop()
        sttEngine?.release()
        ttsEngine?.release()
        keywordSpotter?.release()
        androidStt?.release()
        Logger.i("VoiceService: Stopped")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service if killed by aggressive battery optimization (Xiaomi, Huawei, Oppo, etc.)
        val restartIntent = Intent(this, VoiceService::class.java)
        startForegroundService(restartIntent)
        super.onTaskRemoved(rootIntent)
    }
}