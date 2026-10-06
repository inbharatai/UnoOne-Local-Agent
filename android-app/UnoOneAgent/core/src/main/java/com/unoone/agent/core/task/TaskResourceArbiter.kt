package com.unoone.agent.core.task

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Use ProcessTaskResources, never one arbiter per task. Does not acquire native operationMutex. */
class TaskResourceArbiter internal constructor(private val model: Boolean) {
    class Lease internal constructor(val owner: TaskId, internal val valid: () -> Unit) {
        internal var job: Job? = null
        internal var live = true
        fun checkActive() { check(live) { "Lease released" }; valid() }
    }
    private class UiElement(val lease: Lease) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<UiElement>
    }
    private class ModelElement(val lease: Lease) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ModelElement>
    }
    private val mutex = Mutex()
    private val ownerLock = Any()
    private var active: Lease? = null
    suspend fun <T> withLease(owner: TaskId, checkActive: () -> Unit, block: suspend (Lease) -> T): T {
        val ctx = currentCoroutineContext()
        val existing = if (model) ctx[ModelElement]?.lease else ctx[UiElement]?.lease
        if (existing != null) {
            require(existing.owner == owner && existing.job === ctx[Job]) { "Inherited/concurrent or wrong-owner lease rejected" }
            existing.checkActive(); checkActive()
            return block(existing)
        }
        check(model || ctx[ModelElement] == null) { "Resource order must be UI then model" }
        mutex.lock()
        val lease = Lease(owner, checkActive)
        try {
            ctx.ensureActive(); checkActive()
            synchronized(ownerLock) { active = lease }
            val element: CoroutineContext = if (model) ModelElement(lease) else UiElement(lease)
            return withContext(element) {
                lease.job = currentCoroutineContext()[Job]
                lease.checkActive()
                block(lease)
            }
        } finally {
            synchronized(ownerLock) { lease.live = false; if (active === lease) active = null }
            mutex.unlock()
        }
    }
    /** Native wrappers may reuse only the lease of this exact coroutine, never a child Job. */
    suspend fun currentLease(): Lease? {
        val ctx = currentCoroutineContext()
        val lease = (if (model) ctx[ModelElement]?.lease else ctx[UiElement]?.lease) ?: return null
        require(lease.job === ctx[Job]) { "Inherited/concurrent lease rejected" }
        lease.checkActive()
        return lease
    }

    /** Cancel only a matching owner. Never calls global native cancel on an unrelated task. */
    fun cancelOwner(owner: TaskId): Boolean = synchronized(ownerLock) {
        val lease = active?.takeIf { it.owner == owner } ?: return@synchronized false
        lease.live = false
        lease.job?.cancel()
        true
    }
    fun owner(): TaskId? = synchronized(ownerLock) { active?.owner }
}
object ProcessTaskResources {
    val ui = TaskResourceArbiter(model = false)
    val model = TaskResourceArbiter(model = true)
}
