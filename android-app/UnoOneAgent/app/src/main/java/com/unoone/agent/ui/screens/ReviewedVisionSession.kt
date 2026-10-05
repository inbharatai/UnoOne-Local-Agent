package com.unoone.agent.ui.screens

import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.os.SystemClock
import com.unoone.agent.core.device.*
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.localbrain.NativeImageReviewReceipt
import com.unoone.agent.localbrain.SnapshotImageEnvelope
import java.io.ByteArrayOutputStream

/** No engine, capture, persistence or action dispatch. Consent is never remembered across frames. */
object ReviewedVisionSession {
    fun approve(frame: V3PerceptionSession.Frame, userApprovedLocalProcessing: Boolean): SnapshotImageEnvelope {
        check(userApprovedLocalProcessing && AgentRuntimeGate.isEnabled())
        val snapshot = frame.state.snapshot
        val capture = frame.provenance
        val now = SystemClock.elapsedRealtime()
        check(!frame.bitmap.isRecycled && !snapshot.truncated)
        check(now - snapshot.capturedAtMs in 0..30_000) { "Captured screen expired; capture again" }
        // Conservative deny, rather than claiming complete detection of private canvas content.
        check(snapshot.nodes.none { it.password || it.semantic.sensitiveObservation() }) {
            "Known sensitive screen is not admitted for model analysis"
        }
        // Preserve the complete screen region while scaling its encoding for the mobile encoder.
        // Normalized grounding coordinates remain in the same full-display coordinate system.
        val scale = minOf(1.0, 768.0 / maxOf(frame.bitmap.width, frame.bitmap.height))
        val width = maxOf(1, (frame.bitmap.width * scale).toInt())
        val height = maxOf(1, (frame.bitmap.height * scale).toInt())
        val scaled = frame.bitmap.scale(width, height, filter = true)
        val bytes = try { ByteArrayOutputStream().use { out ->
            check(scaled.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        } } finally { if (scaled !== frame.bitmap) scaled.recycle() }
        return try {
            val receipt = NativeImageReviewReceipt(true, true, SnapshotImageEnvelope.digest(bytes),
                snapshot.id, UiStateHasher.hash(snapshot), snapshot.windows.map { it.packageName }.toSet(),
                snapshot.eventSequence, capture.captureSequence, capture.rotation, now)
            SnapshotImageEnvelope(bytes, snapshot.id, snapshot.eventSequence, snapshot.displayBounds,
                capture.displayBounds, capture.requestedAtNanos, capture.capturedAtNanos,
                capture.captureSequence, capture.rotation, receipt).also {
                it.validatedBytes(frame.state, now).fill(0)
            }
        } finally { bytes.fill(0) }
    }
}
