package com.unoone.agent.localbrain

import com.unoone.agent.core.device.*
import kotlinx.serialization.json.*

/** Native evidence of a per-capture local-processing decision, NOT proof that pixels contain no secrets. */
data class NativeImageReviewReceipt(
    val userApprovedLocalProcessing: Boolean,
    val knownSensitiveMaskApplied: Boolean,
    val imageSha256: String,
    val snapshotId: String,
    val snapshotHash: String,
    val appPackages: Set<String>,
    val eventSequence: Long,
    val captureSequence: Long,
    val rotation: Int,
    val approvedAtMs: Long
)

/** Full-display historical advisory image. Never grants action authorization. */
class SnapshotImageEnvelope(bytes: ByteArray, val snapshotId: String, val eventSequence: Long,
    val displayBounds: RectData, val imageBounds: RectData, val requestedAtNanos: Long,
    val capturedAtNanos: Long, val captureSequence: Long, val rotation: Int,
    val review: NativeImageReviewReceipt) : AutoCloseable {
    private val encoded = bytes.copyOf()
    private var closed = false
    fun validatedBytes(state: PerceptionState, nowMs: Long, maxAgeMs: Long = 30_000): ByteArray {
        val s = state.snapshot
        require(maxAgeMs in 1..30_000)
        require(!closed && encoded.isNotEmpty() && !s.truncated)
        require(review.userApprovedLocalProcessing && review.knownSensitiveMaskApplied)
        require(review.imageSha256 == digest(encoded)) { "Reviewed image digest mismatch" }
        require(review.snapshotId == snapshotId && review.snapshotHash == UiStateHasher.hash(s))
        require(review.appPackages == s.windows.map { it.packageName }.toSet())
        require(review.eventSequence == eventSequence && review.captureSequence == captureSequence && review.rotation == rotation)
        require(snapshotId == s.id && eventSequence == s.eventSequence)
        require(displayBounds == s.displayBounds && imageBounds == displayBounds) { "Cropped model images forbidden" }
        require(rotation in 0..3 && captureSequence > 0)
        require(capturedAtNanos > requestedAtNanos && requestedAtNanos / 1_000_000 >= s.capturedAtMs)
        require(review.approvedAtMs >= capturedAtNanos / 1_000_000 && nowMs >= review.approvedAtMs)
        require(nowMs - capturedAtNanos / 1_000_000 in 0..maxAgeMs && nowMs - s.capturedAtMs in 0..maxAgeMs)
        return encoded.copyOf()
    }
    override fun close() { closed = true; encoded.fill(0) }
    companion object {
        fun digest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Model coordinates are advice, never executable coordinates or new target identities. */
data class GroundingBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val confidence: Double) {
    init {
        require(listOf(left, top, right, bottom, confidence).all { it.isFinite() && it in 0.0..1.0 })
        require(left < right && top < bottom)
    }
    fun matchIssuedTarget(state: PerceptionState): TargetGrounding {
        require(confidence >= 0.8) { "Grounding confidence too low" }
        val d = state.snapshot.displayBounds
        val w = (d.right - d.left).toDouble(); val h = (d.bottom - d.top).toDouble()
        fun matches(r: RectData): Boolean {
            val l = (r.left-d.left)/w; val t = (r.top-d.top)/h
            val rr = (r.right-d.left)/w; val b = (r.bottom-d.top)/h
            val intersection = (minOf(right, rr)-maxOf(left, l)).coerceAtLeast(0.0) *
                (minOf(bottom, b)-maxOf(top, t)).coerceAtLeast(0.0)
            val union = (right-left)*(bottom-top)+(rr-l)*(b-t)-intersection
            return intersection / union >= 0.7
        }
        val nodes = state.snapshot.nodes.filter { it.enabled && !it.password && matches(it.bounds) }
        if (nodes.size == 1) return TargetGrounding(nodeId = nodes.single().id)
        require(nodes.isEmpty()) { "Ambiguous grounding" }
        val visual = state.visualTargets.filter { it.snapshotId == state.snapshot.id && matches(it.bounds) }
        require(visual.size == 1) { "No unique issued target matches grounding" }
        return TargetGrounding(visualTargetId = visual.single().id)
    }
    companion object {
        fun parse(raw: String): GroundingBox {
            require(raw.length <= 2048)
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: error("Expected grounding object")
            require(obj.keys == setOf("left", "top", "right", "bottom", "confidence"))
            fun number(key: String): Double {
                val value = obj[key] as? JsonPrimitive ?: error("Expected number")
                require(!value.isString)
                return value.doubleOrNull ?: error("Expected number")
            }
            return GroundingBox(number("left"), number("top"), number("right"), number("bottom"), number("confidence"))
        }
    }
}
