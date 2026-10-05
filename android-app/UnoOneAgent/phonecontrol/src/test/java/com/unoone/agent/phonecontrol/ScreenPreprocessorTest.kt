package com.unoone.agent.phonecontrol

import android.graphics.Bitmap
import android.graphics.Color
import com.unoone.agent.core.device.RectData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScreenPreprocessorTest {
    @Test fun boundsAndCropRoundTrip() {
        val t = ScreenTransform.plan(1080, 2400)
        assertEquals(768, t.outputHeight)
        assertTrue(t.outputWidth <= 768)
        assertEquals(RectData(0, 0, 1080, 2400), t.toScreen(RectData(0, 0, t.outputWidth, t.outputHeight)))
        val crop = RectData(100, 200, 900, 1800)
        val c = ScreenTransform.plan(1080, 2400, crop)
        assertEquals(crop, c.toScreen(RectData(0, 0, c.outputWidth, c.outputHeight)))
        assertEquals(120, ScreenTransform.plan(120, 200).outputWidth)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfBoundsCrop() {
        ScreenTransform.plan(100, 100, RectData(0, 0, 101, 100))
    }

    @Test fun hashesDuplicatesAndReportsOnlyChangedTile() {
        val source = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        ScreenPreprocessor.prepare(source).use { first ->
            assertFalse(first.duplicate)
            assertEquals(4, first.changedRegions.size)
            assertTrue(first.compressedBytes.isNotEmpty())
            ScreenPreprocessor.prepare(source, previous = first.fingerprint).use { same ->
                assertTrue(same.duplicate)
                assertTrue(same.changedRegions.isEmpty())
            }
            source.setPixel(70, 70, Color.BLACK)
            ScreenPreprocessor.prepare(source, previous = first.fingerprint).use { changed ->
                assertFalse(changed.duplicate)
                assertEquals(listOf(RectData(64, 64, 128, 128)), changed.changedRegions)
            }
        }
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test fun secretsMaskedBeforeEncodingWithoutMutatingSource() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val safe = ScreenPreprocessor.maskSecrets(source, listOf(RectData(10,10,30,30)))
        assertEquals(Color.BLACK, safe.getPixel(20,20))
        assertEquals(Color.WHITE, source.getPixel(20,20))
        assertEquals(Color.WHITE, safe.getPixel(50,50))
        safe.recycle(); source.recycle()
    }
    @Test fun masksVisibleIntersectionAndKeepsOwnershipSeparate() {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        val safe = ScreenPreprocessor.maskSecrets(source, listOf(RectData(10, 10, 30, 30), RectData(30, 30, 40, 40)))
        assertNotSame(source, safe)
        assertEquals(Color.BLACK, safe.getPixel(19, 19))
        assertEquals(Color.WHITE, safe.getPixel(0, 0))
        safe.recycle()
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test fun localPreviewHasNoInferenceAdmissionOrEncodedTransport() {
        val fields = LocalScreenPreview::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("secretsExcluded"))
        assertFalse(fields.contains("compressedBytes"))
        assertFalse(fields.contains("envelope"))
    }

    @Test fun sha256KnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ScreenPreprocessor.sha256(byteArrayOf()))
    }
}
