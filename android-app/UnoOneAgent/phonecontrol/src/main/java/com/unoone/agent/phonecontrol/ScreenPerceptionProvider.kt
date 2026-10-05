package com.unoone.agent.phonecontrol

import android.content.Context
import android.graphics.Bitmap
import com.unoone.agent.core.device.OcrRegion
import com.unoone.agent.core.device.PerceptionFusion
import com.unoone.agent.core.device.PerceptionState
import com.unoone.agent.core.device.RectData
import com.unoone.agent.core.device.UiSnapshot
import com.unoone.agent.core.device.sensitiveObservation
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OCR evidence, not model vision or authorization to act on screen contents. */
data class ScreenObservation(val regions: List<OcrRegion>, val fingerprint: ScreenFingerprint,
    val duplicate: Boolean, val changedRegions: List<RectData>, val compressedBytes: ByteArray,
    val mimeType: String = "image/jpeg",
    val frame: CapturedScreen? = null, val snapshotId: String? = null, val eventSequence: Long? = null) {
    fun fuse(snapshot: UiSnapshot, currentEventSequence: Long, nowMs: Long): PerceptionState {
        val f = requireNotNull(frame) { "Unbound bitmap cannot be fused" }
        require(snapshotId == snapshot.id && eventSequence == snapshot.eventSequence && currentEventSequence == eventSequence)
        require(f.displayBounds == snapshot.displayBounds && f.capturedAtNanos > f.requestedAtNanos)
        require(f.requestedAtNanos / 1_000_000 >= snapshot.capturedAtMs)
        require(nowMs - f.capturedAtMs in 0..5_000 && nowMs - snapshot.capturedAtMs in 0..5_000)
        return PerceptionFusion.fuse(snapshot, regions)
    }
}

/** Local-only masked pixels and OCR. Deliberately has no encoded bytes, admission flag,
 * envelope conversion, or model transport. Unknown canvas secrets remain possible. */
class LocalScreenPreview(val bitmap: Bitmap, val state: PerceptionState) : AutoCloseable {
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

/** No background capture. Prefer observe(bitmap) for an already-authorized frame. */
class ScreenPerceptionProvider(context: Context) : AutoCloseable {
    private val ocr = OcrControl(context.applicationContext)
    private val capture = ScreenshotCapture(context.applicationContext)

    suspend fun observe(bitmap: Bitmap, crop: RectData? = null,
        previous: ScreenFingerprint? = null): Result<ScreenObservation> = withContext(Dispatchers.Default) {
        try {
            ScreenPreprocessor.prepare(bitmap, crop, previous).use { frame ->
                when (val result = ocr.recognizeRegions(frame.bitmap, frame.transform)) {
                    is Result.Success -> Result.Success(ScreenObservation(result.data, frame.fingerprint,
                        frame.duplicate, frame.changedRegions, frame.compressedBytes))
                    is Result.Error -> Result.Error(result.message)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.Error("Screen preprocessing failed: ${e.message}") }
    }

    /** Explicit on-device inspection is not inference admission. Caller retains raw-frame ownership.
     * OCR completes before cleanup (OcrControl uses suspendCoroutine); no recycled pixels reach it. */
    suspend fun previewLocal(frame: CapturedScreen, snapshot: UiSnapshot,
        currentEventSequence: () -> Long, explicitUserRequest: Boolean): Result<LocalScreenPreview> {
        if (!explicitUserRequest || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled() ||
            !ScreenshotCapture.hasPermission()) return Result.Error("Local preview requires explicit permission")
        fun fresh(): Boolean = snapshot.displayBounds == frame.displayBounds &&
            currentEventSequence() == snapshot.eventSequence &&
            frame.capturedAtNanos > frame.requestedAtNanos &&
            frame.requestedAtNanos / 1_000_000 >= snapshot.capturedAtMs &&
            android.os.SystemClock.elapsedRealtime() - snapshot.capturedAtMs in 0..5_000 &&
            android.os.SystemClock.elapsedRealtime() - frame.capturedAtMs in 0..5_000
        if (!fresh()) return Result.Error("Capture provenance mismatch")
        val secret = snapshot.nodes.filter { it.password || it.semantic.sensitiveObservation() }.map { it.bounds }
        val safe = ScreenPreprocessor.maskSecrets(frame.bitmap, secret)
        var transferred = false
        try {
            val regions = when (val result = ocr.recognizeRegions(safe)) {
                is Result.Success -> result.data
                is Result.Error -> return Result.Error("Local OCR unavailable")
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!fresh() || !com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled() ||
                !ScreenshotCapture.hasPermission()) return Result.Error("Capture changed or expired")
            val preview = LocalScreenPreview(safe, PerceptionFusion.fuse(snapshot, regions))
            transferred = true
            return Result.Success(preview)
        } finally { if (!transferred) safe.recycle() }
    }

    /** Trusted native caller must establish exclusion for ALL pixels, not infer it from OCR.
     * Default deny: an accessibility tree alone cannot prove absence of secrets in canvas pixels. */
    suspend fun observe(frame: CapturedScreen, snapshot: UiSnapshot, currentEventSequence: () -> Long,
        secretsExcludedOutsideKnownRegions: Boolean = false): Result<ScreenObservation> {
        if (!secretsExcludedOutsideKnownRegions || snapshot.truncated)
            return Result.Error("Cannot establish full-screen secret exclusion; user handover required")
        if (snapshot.displayBounds != frame.displayBounds || currentEventSequence() != snapshot.eventSequence ||
            frame.requestedAtNanos / 1_000_000 < snapshot.capturedAtMs)
            return Result.Error("Capture provenance mismatch")
        val secret = snapshot.nodes.filter { it.password || it.semantic.sensitiveObservation() }.map { it.bounds }
        val safe = ScreenPreprocessor.maskSecrets(frame.bitmap, secret)
        return try {
            when (val result = observe(safe)) {
                is Result.Success -> {
                    val bound = result.data.copy(frame = frame, snapshotId = snapshot.id, eventSequence = snapshot.eventSequence)
                    try { bound.fuse(snapshot, currentEventSequence(), android.os.SystemClock.elapsedRealtime()) }
                    catch (e: Exception) { bound.compressedBytes.fill(0); return Result.Error("Capture changed or expired") }
                    Result.Success(bound)
                }
                is Result.Error -> result
            }
        } finally { safe.recycle() }
    }

    /** Legacy unbound capture cannot prove privacy or snapshot provenance. */
    suspend fun captureAuthorized(explicitUserRequest: Boolean, crop: RectData? = null,
        previous: ScreenFingerprint? = null): Result<ScreenObservation> =
        Result.Error("Snapshot-bound capture and native secret exclusion proof required")

    override fun close() = ocr.release()
}
