package com.unoone.agent.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.unoone.agent.accessibilitycontrol.AndroidDeviceAdapter
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.device.PerceptionState
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.phonecontrol.ScreenPerceptionProvider
import com.unoone.agent.phonecontrol.ScreenshotCapture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit, ephemeral observation only. Never dispatches an action or persists pixels. */
class V3PerceptionSession(private val context: Context) {
    class Frame(val bitmap: Bitmap, val state: PerceptionState, val provenance: com.unoone.agent.phonecontrol.CapturedScreen) : AutoCloseable {
        override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    suspend fun capture(explicitUserRequest: Boolean, maxAgeMs: Long = 5_000): Frame {
        require(explicitUserRequest && maxAgeMs in 1..5_000)
        check(AgentRuntimeGate.isEnabled()) { "Master disabled" }
        check(ScreenshotCapture.hasPermission()) { "Granted MediaProjection required" }
        val service = UnoOneAccessibilityService.getInstance() ?: error("Accessibility unavailable")
        // Wait for observed event quiescence, with a bounded deadline, BEFORE snapshotting.
        val deadline = SystemClock.elapsedRealtime() + 1_500
        var sequence = service.eventSequence
        var quietSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - quietSince < 200) {
            check(SystemClock.elapsedRealtime() < deadline) { "Screen did not settle" }
            delay(50)
            if (sequence != service.eventSequence) {
                sequence = service.eventSequence
                quietSince = SystemClock.elapsedRealtime()
            }
        }
        val snapshot = AndroidDeviceAdapter(service).observe().snapshot
        fun validate() {
            check(AgentRuntimeGate.isEnabled() && ScreenshotCapture.hasPermission())
            check(UnoOneAccessibilityService.getInstance() === service)
            check(service.eventSequence == snapshot.eventSequence) { "Screen changed; capture discarded" }
            check(SystemClock.elapsedRealtime() - snapshot.capturedAtMs in 0..maxAgeMs) { "Capture expired" }
        }
        validate()
        // Outer ownership covers prompt cancellation while returning from the IO dispatcher.
        var owned: Bitmap? = null
        try {
        val frame = withContext(Dispatchers.IO) {
            val captured = when (val result = ScreenshotCapture(context).captureFrame()) {
                is Result.Success -> result.data.also { owned = it.bitmap }
                is Result.Error -> error("Capture unavailable")
            }
            val bitmap = captured.bitmap
            try {
                validate()
                check(bitmap.width == snapshot.displayBounds.right && bitmap.height == snapshot.displayBounds.bottom) { "Display geometry changed" }
                val preview = ScreenPerceptionProvider(context).use { provider ->
                    when (val result = provider.previewLocal(captured, snapshot, { service.eventSequence }, explicitUserRequest)) {
                        is Result.Success -> result.data
                        is Result.Error -> error("Local preview/OCR unavailable")
                    }
                }
                bitmap.recycle()
                owned = preview.bitmap
                validate()
                Frame(preview.bitmap, preview.state, captured.copy(bitmap = preview.bitmap))
            } catch (e: Throwable) { if (!bitmap.isRecycled) bitmap.recycle(); throw e }
        }
        validate()
        owned = null
        return frame
        } finally { owned?.let { if (!it.isRecycled) it.recycle() } }
    }
}
