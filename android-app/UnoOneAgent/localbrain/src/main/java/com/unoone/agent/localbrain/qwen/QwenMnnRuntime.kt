package com.unoone.agent.localbrain.qwen

/** Blocking CPU runtime. Call load/generate/close off the UI thread under the application's
 * global model lease. A false close acknowledgement MUST keep that lease held.
 * Cancellation is cooperative at native token boundaries, not an unload acknowledgement.
 * No device/model qualification is implied by library availability.
 */
class QwenMnnRuntime {
    private val lock = Any()
    private var handle = 0L
    private var cancellationGeneration = 0L
    class RequestPermit internal constructor(internal val handle: Long, internal val generation: Long, internal val nativeEpoch: Long)
    /** Capture before waiting for any shared model gate; never refresh this permit. */
    fun admitRequest(): RequestPermit = synchronized(lock) {
        check(!cleanupUncertain) { "Native cleanup is uncertain; restart required" }
        RequestPermit(handle, cancellationGeneration, if (handle != 0L) nativeEpoch(handle) else 0L)
    }
    private var generating = false
    private var loading = false
    private var closing = false
    private var cleanupUncertain = false
    private var outputLimit = 256
    private var visual = false
    val imageSupported: Boolean get() = synchronized(lock) { handle != 0L && visual && !cleanupUncertain && !closing }
    @Volatile var resolvedConfigReceipt: String? = null
        private set
    val backend: String get() = "MNN CPU arm64 / 024a946b0b8fcf87c8a418229fadd4cd7858ffba"
    val status: String get() = synchronized(lock) {
        when { cleanupUncertain -> "QUARANTINED"; loading -> "LOADING"; generating -> "GENERATING"; handle != 0L -> "LOADED"; else -> "UNLOADED" }
    }

    /** Observed post-load MNN config; the original file is never rewritten. */
    fun load(configPath: String, contextLimit: Int = 2048, maxOutput: Int = 256): String {
        require(contextLimit in 1..4096 && maxOutput in 1..256 && maxOutput < contextLimit)
        require(configPath.startsWith("/") && !configPath.contains(0.toChar()))
        val loadGeneration = synchronized(lock) { check(handle == 0L && !loading && !closing); loading = true; cancellationGeneration }
        var loaded = 0L
        try {
            System.loadLibrary("unoone_qwen")
            loaded = nativeLoad(configPath.toByteArray(Charsets.UTF_8), contextLimit, maxOutput)
            check(loaded != 0L)
            val supportsImages = nativeImageSupported(loaded)
            val receipt = nativeReceipt(loaded).toString(Charsets.UTF_8)
            synchronized(lock) { check(loadGeneration == cancellationGeneration) { "Load cancelled" }; handle = loaded; visual = supportsImages; outputLimit = maxOutput; resolvedConfigReceipt = receipt }
            return receipt
        } catch (e: Throwable) {
            if (loaded != 0L) {
                var acknowledged = false
                try { acknowledged = nativeClose(loaded) }
                catch (cleanup: Throwable) { synchronized(lock) { cleanupUncertain = true }; e.addSuppressed(cleanup) }
                synchronized(lock) {
                    // An unpublished handle is still a native allocation until destruction ACK.
                    handle = if (acknowledged) 0L else loaded
                    visual = false
                    resolvedConfigReceipt = null
                }
            }
            throw e
        } finally { synchronized(lock) { loading = false } }
    }

    /** Encoded PNG/JPEG bytes (not a path); null means text-only. Chunk bytes may split UTF-8.
     * onChunk is synchronous on the generation thread; it must not call generate/load.
     */
    fun generate(system: String, text: String, imageBytes: ByteArray? = null,
                 tokenLimit: Int = 256, onChunk: ((ByteArray) -> Unit)? = null,
                 permit: RequestPermit = admitRequest()): String {
        require(tokenLimit in 1..256)
        require(system.length + text.length <= 32768)
        require(imageBytes == null || imageBytes.size in 1..4194304) { "Encoded image budget exceeded" }
        val h = synchronized(lock) {
            check(permit.handle == handle && permit.generation == cancellationGeneration) { "Stale request permit" }
            check(handle != 0L && !generating && !closing && !loading && !cleanupUncertain) { "Runtime unavailable/busy" }
            require(tokenLimit <= outputLimit)
            require(imageBytes == null || visual) { "Loaded model does not support images" }
            nativePrepare(handle, permit.nativeEpoch)
            generating = true
            handle
        }
        return try {
            nativeGenerate(h, permit.nativeEpoch, system.toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8),
                imageBytes, tokenLimit, object : ChunkCallback {
                    override fun onChunk(bytes: ByteArray) { onChunk?.invoke(bytes) }
                }).toString(Charsets.UTF_8)
        } finally { synchronized(lock) { generating = false } }
    }

    fun cancel() = synchronized(lock) { cancellationGeneration++; if (handle != 0L && !closing) nativeCancel(handle) }

    /** True only after native destruction completed (or already unloaded). Never closes busy model. */
    fun close(): Boolean {
        val h = synchronized(lock) {
            cancellationGeneration++
            if (cleanupUncertain) return false
            if (generating || loading || closing) {
                if (handle != 0L && !closing) nativeCancel(handle)
                return false
            }
            if (handle == 0L) return true
            closing = true
            handle
        }
        return try {
            val closed = nativeClose(h)
            if (closed) synchronized(lock) { handle = 0L; visual = false; resolvedConfigReceipt = null }
            closed
        } catch (failure: Throwable) {
            synchronized(lock) { cleanupUncertain = true }
            throw failure
        } finally { synchronized(lock) { closing = false } }
    }

    // Keep via consumer-rules.pro: these names form the JNI ABI.
    interface ChunkCallback { fun onChunk(bytes: ByteArray) }
    private external fun nativeImageSupported(handle: Long): Boolean
    private external fun nativeEpoch(handle: Long): Long
    private external fun nativePrepare(handle: Long, expectedEpoch: Long)
    private external fun nativeLoad(configPath: ByteArray, contextLimit: Int, maxOutput: Int): Long
    private external fun nativeReceipt(handle: Long): ByteArray
    private external fun nativeGenerate(handle: Long, expectedEpoch: Long, system: ByteArray, text: ByteArray,
                                       image: ByteArray?, tokenLimit: Int, callback: ChunkCallback): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long): Boolean
}
