package com.unoone.agent.localbrain

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unoone.agent.core.device.*
import com.unoone.agent.phonecontrol.ScreenPreprocessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android bitmap/preprocessing and public native schema; does not claim model qualification. */
@RunWith(AndroidJUnit4::class)
class V3FixtureBoundaryInstrumentedTest {
    @Test fun malformedUnknownAndStaleActionsFailClosed() {
        val state = V3PixelFixture.state()
        val now = SystemClock.elapsedRealtime()
        val valid = DeviceAction.ClickNode(state.snapshot.id, "search")
        assertEquals(valid, DeviceActionCodec.decode(DeviceActionCodec.encode(valid), state, now))
        listOf(
            """[{"type":"Observe"}]""",
            """{"type":"Observe","extra":true}""",
            """{"type":"ClickNode","snapshotId":"wrong","nodeId":"search"}""",
            """{"type":"ClickNode","snapshotId":"v3-synthetic","nodeId":"invented"}""",
            """{"type":"Wait","durationMs":2001}"""
        ).forEach { raw -> assertTrue("Accepted invalid schema: $raw", runCatching {
            DeviceActionCodec.decode(raw, state, now)
        }.isFailure) }
        assertTrue(runCatching {
            DeviceActionCodec.decode(DeviceActionCodec.encode(valid), state,
                state.snapshot.capturedAtMs + DeviceActionValidator.MAX_SNAPSHOT_AGE_MS + 1)
        }.isFailure)
    }

    @Test fun fullDisplayEnvelopeRejectsWrongSnapshotAndUnreviewedPixels() {
        val state = V3PixelFixture.state()
        val bitmap = V3PixelFixture.bitmap()
        try {
            val bytes = V3PixelFixture.png(bitmap)
            val requested = SystemClock.elapsedRealtimeNanos()
            val captured = SystemClock.elapsedRealtimeNanos().coerceAtLeast(requested + 1)
            fun envelope(id: String, safe: Boolean) = SnapshotImageEnvelope(bytes, id, 1,
                V3PixelFixture.display, V3PixelFixture.display, requested, captured, 1, 0, NativeImageReviewReceipt(safe, true, SnapshotImageEnvelope.digest(bytes), id,
                    com.unoone.agent.core.device.UiStateHasher.hash(state.snapshot), state.snapshot.windows.map { it.packageName }.toSet(),
                    1, 1, 0, captured / 1_000_000))
            assertArrayEquals(bytes, envelope(state.snapshot.id, true).validatedBytes(state, SystemClock.elapsedRealtime()))
            assertTrue(runCatching { envelope("wrong", true).validatedBytes(state, SystemClock.elapsedRealtime()) }.isFailure)
            assertTrue(runCatching { envelope(state.snapshot.id, false).validatedBytes(state, SystemClock.elapsedRealtime()) }.isFailure)
        } finally { bitmap.recycle() }
    }

    @Test fun fiftyRealBitmapPreprocessCyclesDuplicateAndChangeDetection() {
        val source = V3PixelFixture.bitmap()
        try {
            val baseline = ScreenPreprocessor.prepare(source)
            val fingerprint = baseline.fingerprint
            assertFalse(baseline.duplicate)
            assertTrue(baseline.changedRegions.isNotEmpty())
            baseline.close()
            assertTrue(baseline.bitmap.isRecycled)
            repeat(50) {
                val prepared = ScreenPreprocessor.prepare(source, previous = fingerprint)
                try {
                    assertTrue(prepared.duplicate)
                    assertTrue(prepared.changedRegions.isEmpty())
                    assertEquals(V3PixelFixture.display, prepared.transform.toScreen(V3PixelFixture.display))
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(prepared.compressedBytes, 0, prepared.compressedBytes.size))
                    try { assertEquals(640, decoded.width); assertEquals(360, decoded.height) }
                    finally { decoded.recycle() }
                } finally { prepared.close() }
                assertTrue(prepared.bitmap.isRecycled)
                assertFalse(source.isRecycled)
            }
            Canvas(source).drawRect(0f, 0f, 32f, 32f, Paint().apply { color = Color.BLACK })
            val changed = ScreenPreprocessor.prepare(source, previous = fingerprint)
            try { assertFalse(changed.duplicate); assertTrue(changed.changedRegions.isNotEmpty()) }
            finally { changed.close() }
        } finally { source.recycle() }
    }
}
