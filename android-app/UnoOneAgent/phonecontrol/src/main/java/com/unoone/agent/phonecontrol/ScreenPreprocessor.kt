package com.unoone.agent.phonecontrol

import android.graphics.Bitmap
import com.unoone.agent.core.device.RectData
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Coordinates in output pixels map back to the original, uncropped screen. */
data class ScreenTransform(val sourceWidth: Int, val sourceHeight: Int, val crop: RectData,
    val outputWidth: Int, val outputHeight: Int) {
    init {
        require(sourceWidth in 1..32768 && sourceHeight in 1..32768)
        require(RectData(0, 0, sourceWidth, sourceHeight).contains(crop))
        require(outputWidth in 1..768 && outputHeight in 1..768)
    }
    fun toScreen(bounds: RectData): RectData {
        require(RectData(0, 0, outputWidth, outputHeight).contains(bounds))
        val sx = (crop.right - crop.left).toDouble() / outputWidth
        val sy = (crop.bottom - crop.top).toDouble() / outputHeight
        return RectData(crop.left + floor(bounds.left * sx).toInt(), crop.top + floor(bounds.top * sy).toInt(),
            (crop.left + ceil(bounds.right * sx).toInt()).coerceAtMost(crop.right),
            (crop.top + ceil(bounds.bottom * sy).toInt()).coerceAtMost(crop.bottom))
    }
    companion object {
        fun plan(width: Int, height: Int, crop: RectData? = null, maxDimension: Int = 768): ScreenTransform {
            require(maxDimension in 1..768)
            val area = crop ?: RectData(0, 0, width, height)
            val w = area.right - area.left
            val h = area.bottom - area.top
            val scale = minOf(1.0, maxDimension.toDouble() / maxOf(w, h))
            return ScreenTransform(width, height, area, (w * scale).roundToInt().coerceAtLeast(1),
                (h * scale).roundToInt().coerceAtLeast(1))
        }
    }
}

data class ScreenFingerprint(val transform: ScreenTransform, val sha256: String, val tiles: List<String>)

/** Owns bitmap; close after OCR. No disk persistence and no network transport. */
class PreparedScreen(val bitmap: Bitmap, val compressedBytes: ByteArray, val fingerprint: ScreenFingerprint,
    val duplicate: Boolean, val changedRegions: List<RectData>) : AutoCloseable {
    val transform get() = fingerprint.transform
    val mimeType: String = "image/jpeg"
    override fun close() { bitmap.recycle() }
}

object ScreenPreprocessor {
    /** Mask original pixels before resizing, JPEG encoding, fingerprinting or OCR. */
    fun maskSecrets(source: Bitmap, regions: List<RectData>): Bitmap {
        require(!source.isRecycled)
        // Accessibility bounds may straddle the display edge. Mask their visible intersection.
        val visible = regions.mapNotNull { r ->
            val left = r.left.coerceIn(0, source.width)
            val top = r.top.coerceIn(0, source.height)
            val right = r.right.coerceIn(0, source.width)
            val bottom = r.bottom.coerceIn(0, source.height)
            if (right > left && bottom > top) RectData(left, top, right, bottom) else null
        }
        val safe = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        try {
            // Explicit opaque pixel replacement: no blending, filtering or drawing-backend variance.
            visible.forEach { r ->
                val width = r.right - r.left
                val row = IntArray(width) { android.graphics.Color.BLACK }
                for (y in r.top until r.bottom) safe.setPixels(row, 0, width, r.left, y, width, 1)
            }
            return safe
        } catch (error: Throwable) { safe.recycle(); throw error }
    }
    private const val TILE = 64
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** Does not mutate/recycle caller input. Previous fingerprint must be from this same pipeline. */
    fun prepare(source: Bitmap, crop: RectData? = null, previous: ScreenFingerprint? = null,
        maxDimension: Int = 768): PreparedScreen {
        require(!source.isRecycled)
        val t = ScreenTransform.plan(source.width, source.height, crop, maxDimension)
        val area = t.crop
        val cropped = Bitmap.createBitmap(source, area.left, area.top, area.right - area.left, area.bottom - area.top)
        val scaled = Bitmap.createScaledBitmap(cropped, t.outputWidth, t.outputHeight, true)
        val owned = if (scaled === source) requireNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false)) else scaled
        if (cropped !== source && cropped !== owned) cropped.recycle()
        try {
            val tiles = mutableListOf<String>()
            val regions = mutableListOf<RectData>()
            val all = ByteArrayOutputStream()
            for (y in 0 until owned.height step TILE) for (x in 0 until owned.width step TILE) {
                val w = minOf(TILE, owned.width - x)
                val h = minOf(TILE, owned.height - y)
                val pixels = IntArray(w * h)
                owned.getPixels(pixels, 0, w, x, y, w, h)
                val bytes = ByteArray(pixels.size * 4)
                pixels.forEachIndexed { i, p -> for (b in 0..3) bytes[i * 4 + b] = (p ushr (b * 8)).toByte() }
                all.write(bytes)
                val hash = sha256(bytes)
                if (previous?.transform != t || previous.tiles.getOrNull(tiles.size) != hash)
                    regions.add(t.toScreen(RectData(x, y, x + w, y + h)))
                tiles.add(hash)
            }
            val fingerprint = ScreenFingerprint(t, sha256(all.toByteArray()), tiles.toList())
            val output = ByteArrayOutputStream()
            check(owned.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "Image encoding failed" }
            return PreparedScreen(owned, output.toByteArray(), fingerprint,
                previous?.transform == t && previous.sha256 == fingerprint.sha256, regions)
        } catch (e: Exception) { owned.recycle(); throw e }
    }
}
