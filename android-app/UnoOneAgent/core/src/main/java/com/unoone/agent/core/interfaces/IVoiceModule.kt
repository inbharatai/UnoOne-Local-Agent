package com.unoone.agent.core.interfaces

import android.content.Context
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.Deferred

/**
 * Abstraction for voice input/output. Enables testing voice commands
 * without actual audio hardware or STT/TTS engines.
 */
interface IVoiceModule {
    fun initStt(modelPath: String): Result<Unit>
    fun startRecording(context: Context, viewModel: Any): Deferred<Result<String>>?
    fun stopAndTranscribe(): Result<String>
    fun speak(text: String): com.unoone.agent.core.model.Result<Unit>
    fun release()
    val isRecording: Boolean
}