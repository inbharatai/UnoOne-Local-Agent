package com.unoone.agent.localbrain.owl

/** Blocking CPU runtime. Call load/generate/close off the UI thread under the application's
 * global model lease. A false close acknowledgement MUST keep that lease held.
 * Cancellation is cooperative at native token boundaries, not an unload acknowledgement.
 * No device/model qualification is implied by library availability.
 */
class OwlLlamaRuntime {
    private val lock = Any()
    private var handle = 0L
    private var cancellationGeneration = 0L
    private var loadCancellationGeneration = 0L
    class LoadPermit internal constructor(internal val runtime: OwlLlamaRuntime, internal val generation: Long)
    fun admitLoad(): LoadPermit = synchronized(lock) { LoadPermit(this, loadCancellationGeneration) }
    private fun validateLoad(permit: LoadPermit) {
        check(permit.runtime === this && permit.generation == loadCancellationGeneration) { "Stale load permit" }
    }
    // Host barrier seam; never replaces native inference in production.
    internal var loadStage: ((String) -> Unit)? = null
    class RequestPermit internal constructor(internal val handle: Long, internal val generation: Long, internal val nativeEpoch: Long, internal val deadline: Long = System.nanoTime() + 180_000_000_000L)
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
    val backend: String get() = "llama.cpp CPU / 4f5406761517648c23dbd60ea5ade37f77a316c9"
    val status: String get() = synchronized(lock) {
        when { cleanupUncertain -> "QUARANTINED"; loading -> "LOADING"; generating -> "GENERATING"; handle != 0L -> "LOADED"; else -> "UNLOADED" }
    }

    /** Successful native-load budget receipt; model files are never rewritten. */
    fun load(configPath: String, contextLimit: Int = 2048, maxOutput: Int = 256, imageEdge: Int = 512, permit: LoadPermit = admitLoad()): String {
        require((contextLimit == 2048 && maxOutput == 256 && imageEdge == 512) || (contextLimit == 1024 && maxOutput == 128 && imageEdge == 256))
        val root = java.io.File(configPath).canonicalFile
        val decoder = java.io.File(root, "GUI-Owl-1.5-4B-Instruct.Q4_K_M.gguf").canonicalFile
        val projector = java.io.File(root, "GUI-Owl-1.5-4B-Instruct.mmproj-Q8_0.gguf").canonicalFile
        require(decoder.parentFile == root && projector.parentFile == root)
        require(decoder.length() == 2497282208L && projector.length() == 453974336L)
        require(configPath.startsWith("/") && !configPath.contains(0.toChar()))
        synchronized(lock) { validateLoad(permit); check(handle == 0L && !loading && !closing && !cleanupUncertain); loading = true }
        var loaded = 0L
        try {
            System.loadLibrary("unoone_owl")
            synchronized(lock) {
                validateLoad(permit)
                loaded = nativeCreate(contextLimit, maxOutput, imageEdge)
                check(loaded != 0L)
                validateLoad(permit)
                handle = loaded // Publish BEFORE blocking load, so stop reaches model/projector loading.
            }
            loadStage?.invoke("published")
            synchronized(lock) { validateLoad(permit) }
            nativeLoad(loaded, 0L, decoder.path.toByteArray(Charsets.UTF_8), projector.path.toByteArray(Charsets.UTF_8))
            loadStage?.invoke("loaded")
            val supportsImages = true
            val receipt = "llama.cpp=4f5406761517648c23dbd60ea5ade37f77a316c9;cpu=2;mmap=true;repack=false;gpu=false;ctx=$contextLimit;output=$maxOutput;imageEdge=$imageEdge;visionTokens=${(imageEdge/32)*(imageEdge/32)};mrope=mtmd;deepstack=mtmd;hostReduced=${contextLimit == 1024};deviceQualified=false"
            synchronized(lock) { validateLoad(permit); handle = loaded; visual = supportsImages; outputLimit = maxOutput; resolvedConfigReceipt = receipt }
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

    /** Complete EOS-terminated UTF-8 only; cancelled/truncated outputs throw, never stream partial actions. */
    fun generate(system: String, text: String, imageBytes: ByteArray? = null,
                 tokenLimit: Int = 256,
                 permit: RequestPermit = admitRequest()): String {
        require(tokenLimit in 1..256)
        require(system.length + text.length <= 32768)
        require(imageBytes == null || imageBytes.size in 1..4194304) { "Encoded image budget exceeded" }
        val h = synchronized(lock) {
            check(permit.handle == handle && permit.generation == cancellationGeneration) { "Stale request permit" }
            check(handle != 0L && !generating && !closing && !loading && !cleanupUncertain) { "Runtime unavailable/busy" }
            require(tokenLimit <= outputLimit)
            require(imageBytes == null || visual) { "Loaded model does not support images" }
            check(nativeEpoch(handle) == permit.nativeEpoch) { "Native request cancelled" }
            generating = true
            handle
        }
        return try {
            nativeGenerate(h, permit.nativeEpoch, strictUtf8(system), strictUtf8(text),
                imageBytes, tokenLimit, ((permit.deadline - System.nanoTime()) / 1_000_000L).also { check(it > 0) { "Request deadline expired in queue" } }).let { bytes ->
                    Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
        } finally { synchronized(lock) { generating = false } }
    }

    fun cancel() = synchronized(lock) { cancellationGeneration++; loadCancellationGeneration++; if (handle != 0L && !closing) nativeCancel(handle) }

    /** True only after native destruction completed (or already unloaded). Never closes busy model. */
    fun close(): Boolean = closeImpl(null)
    /** Maintenance close preserves ONLY the original pre-queue permit; Stop still invalidates it. */
    internal fun closeForReload(permit: LoadPermit): Boolean = closeImpl(permit)
    private fun closeImpl(permit: LoadPermit?): Boolean {
        val h = synchronized(lock) {
            if (permit == null) loadCancellationGeneration++ else validateLoad(permit)
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

    /** Reject unpaired UTF-16 surrogates instead of silently replacing them on the JNI boundary. */
    private fun strictUtf8(value: String): ByteArray {
        val encoded = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Malformed UTF-16 input; cannot encode UTF-8", failure)
        }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    // Same ABI on host JVM and Android; no test-only inference replacement.
    private external fun nativeCreate(contextLimit: Int, maxOutput: Int, imageEdge: Int): Long
    private external fun nativeLoad(handle: Long, epoch: Long, decoder: ByteArray, projector: ByteArray)
    private external fun nativeEpoch(handle: Long): Long
    private external fun nativeGenerate(handle: Long, expectedEpoch: Long, system: ByteArray, text: ByteArray,
                                       image: ByteArray?, tokenLimit: Int, timeoutMs: Long): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long): Boolean
}
