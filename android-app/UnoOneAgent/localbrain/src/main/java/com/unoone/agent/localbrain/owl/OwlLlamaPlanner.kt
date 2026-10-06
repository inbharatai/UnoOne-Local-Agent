package com.unoone.agent.localbrain.owl

import com.unoone.agent.core.model.*
import com.unoone.agent.localbrain.owl.OwlLlamaRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One LLAMA_CPP allocation, participating in the SAME gate as LiteRT. No automatic model fallback. */
class OwlLlamaPlanner(private val residentOwner: String = E4bRuntimeCoordinator.PHONE_OWNER) {
    internal val runtime = OwlLlamaRuntime()
    private val admissionLock = Any()
    internal var loadStage: ((String) -> Unit)? = null
    private val cancelled = AtomicLong()
    @Volatile private var spec: BrainModelSpec? = null
    @Volatile private var error = ""
    private var owner = E4bRuntimeCoordinator.PHONE_OWNER
    private var claimed = false
    private var task = ""
    fun isLoaded() = spec != null
    fun loadedProfile() = spec
    fun supportsImages(): Boolean = isLoaded() && runtime.imageSupported
    fun configReceipt(): String? = runtime.resolvedConfigReceipt
    fun activeBackend() = if (isLoaded()) runtime.backend else ""
    fun lastLoadError() = error

    /** Caller MUST supply the exact bundle root only after complete ModelManager verification. */
    suspend fun load(bundleRoot: String, selected: BrainModelSpec,
        ownerToken: String = E4bRuntimeCoordinator.PHONE_OWNER): Result<Unit> {
        val (loadEpoch, loadPermit) = synchronized(admissionLock) { cancelled.get() to runtime.admitLoad() }
        return withContext(Dispatchers.IO) {
        E4bRuntimeCoordinator.operationMutex.withLock {
            if (loadEpoch != cancelled.get()) throw CancellationException("Owl load cancelled in queue")
            if (selected.id != BrainModelId.GUI_OWL_1_5_4B_INSTRUCT || selected.runtime != BrainRuntime.LLAMA_CPP || !File(bundleRoot).isAbsolute || !File(bundleRoot).isDirectory)
                return@withLock Result.Error("Expected verified absolute GUI-Owl bundle")
            if (!E4bRuntimeCoordinator.canReplaceAllocation(this@OwlLlamaPlanner, ownerToken))
                return@withLock Result.Error("Native allocation owned or reserved by another runtime")
            if (!closeLocked(loadPermit)) return@withLock Result.Error("Native close not acknowledged")
            // Authorization token may be the browser's during restoration; residency still
            // belongs to this planner's declared role, not to the temporary transition token.
            owner = residentOwner
            if (!E4bRuntimeCoordinator.claimAllocation(this@OwlLlamaPlanner, ownerToken, owner))
                return@withLock Result.Error("Native allocation claim refused")
            claimed = true
            E4bRuntimeCoordinator.transition(if (isPhone()) E4bRuntimeState.LOADING_PHONE else E4bRuntimeState.LOADING_BROWSER, owner)
            try {
                val receipt = coroutineScope {
                    val finished = AtomicBoolean(false)
                    val monitor = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (!finished.get()) requestCancel() }
                    }
                    try { loadStage?.invoke("beforeRuntime"); runtime.load(bundleRoot, permit = loadPermit) }
                    finally { finished.set(true); monitor.cancel() }
                }
                check(receipt.isNotBlank()) { "Native config receipt missing" }
                currentCoroutineContext().ensureActive()
                loadStage?.invoke("postRuntime")
                synchronized(admissionLock) {
                    if (loadEpoch != cancelled.get()) throw CancellationException("Owl load cancelled before READY")
                    spec = selected
                    error = ""
                    ready()
                }
                Result.Success(Unit)
            } catch (failure: Throwable) {
                closeLocked()
                error = "LLAMA_CPP load failed"
                if (failure is CancellationException) throw failure
                Result.Error(error)
            }
        }
    }
    }
    private fun isPhone() = owner == E4bRuntimeCoordinator.PHONE_OWNER
    private fun ready() = E4bRuntimeCoordinator.transition(if (isPhone()) E4bRuntimeState.PHONE_READY else E4bRuntimeState.BROWSER_READY, owner)
    fun requestCancel(reason: String = "stop") { synchronized(admissionLock) { cancelled.incrementAndGet(); runtime.cancel() } }
    /** Cancellation acknowledgement only; never unloads or releases allocation ownership. */
    suspend fun cancelAndAwaitIdle(): Boolean {
        requestCancel("browser stop")
        return withTimeoutOrNull(5_000L) {
            E4bRuntimeCoordinator.operationMutex.withLock {
                runtime.status == "LOADED" || runtime.status == "UNLOADED"
            }
        } ?: false
    }
    suspend fun close(): Boolean = withContext(Dispatchers.IO) {
        requestCancel()
        val gate = E4bRuntimeCoordinator.operationMutex
        if (!gate.tryLock()) return@withContext false
        try { closeLocked() } finally { gate.unlock() }
    }
    private fun closeLocked(permit: OwlLlamaRuntime.LoadPermit? = null): Boolean {
        val closed = try { if (permit == null) runtime.close() else runtime.closeForReload(permit) } catch (_: Throwable) {
            if (claimed) E4bRuntimeCoordinator.quarantine(this)
            return false
        }
        if (!closed) {
            if (claimed && runtime.status == "QUARANTINED") E4bRuntimeCoordinator.quarantine(this)
            return false
        } // Ownership retained until destruction ACK.
        spec = null
        task = ""
        if (claimed) {
            E4bRuntimeCoordinator.acknowledgeClosed(this)
            claimed = false
            E4bRuntimeCoordinator.transition(E4bRuntimeState.UNLOADED)
        }
        return true
    }

    /** One request, no implicit native conversation history; never returns cancelled partial output. */
    suspend fun controllerRequest(system: String, prompt: String, image: ByteArray? = null,
        maxOutputTokens: Int = 256): Result<String> {
        // Admission precedes dispatcher queuing as well as the shared native-operation gate.
        val epoch = cancelled.get()
        val permit = try { runtime.admitRequest() } catch (_: Exception) {
            return Result.Error("LLAMA_CPP native runtime is unavailable or quarantined")
        }
        return withContext(Dispatchers.IO) {
        E4bRuntimeCoordinator.operationMutex.withLock {
            if (!isLoaded()) return@withLock Result.Error("LLAMA_CPP model not loaded")
            if (epoch != cancelled.get()) throw CancellationException("LLAMA_CPP request cancelled")
            if (system.length > 16000 || prompt.length > 12000 || maxOutputTokens !in 1..256)
                return@withLock Result.Error("LLAMA_CPP request exceeds bounded input/output policy")
            E4bRuntimeCoordinator.transition(if (isPhone()) E4bRuntimeState.PHONE_INFERENCING else E4bRuntimeState.BROWSER_INFERENCING, owner)
            try {
                val output = coroutineScope {
                    val finished = AtomicBoolean(false)
                    val monitor = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (!finished.get()) requestCancel() }
                    }
                    try { runtime.generate(system, prompt, image, maxOutputTokens, permit = permit) }
                    finally { finished.set(true); monitor.cancel() }
                }
                currentCoroutineContext().ensureActive()
                if (epoch != cancelled.get()) throw CancellationException("LLAMA_CPP request cancelled")
                Result.Success(output)
            } catch (failure: CancellationException) { requestCancel(); throw failure }
            catch (_: Exception) { Result.Error("LLAMA_CPP generation failed; no proposal accepted") }
            finally {
                if (runtime.status == "QUARANTINED") { E4bRuntimeCoordinator.quarantine(this@OwlLlamaPlanner); E4bRuntimeCoordinator.transition(E4bRuntimeState.FAILED, owner, "Native cleanup uncertain") }
                else ready()
            }
        }
        }
    }
    suspend fun request(system: String, text: String, imageBytes: ByteArray? = null): Result<String> =
        controllerRequest(system, text, imageBytes)
    suspend fun chat(command: String, responseLanguage: String = ""): Result<String> =
        Result.Error("GUI-Owl generic chat is unsupported; use explicit screenshot controller")
}
