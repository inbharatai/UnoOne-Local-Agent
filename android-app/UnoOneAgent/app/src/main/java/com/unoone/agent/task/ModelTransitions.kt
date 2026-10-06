package com.unoone.agent.task

import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.core.runtime.GlobalTaskCancellation
import com.unoone.agent.core.task.ProcessTaskResources
import com.unoone.agent.core.task.TaskId
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID

/** Scheduling only: native engine operationMutex and quarantine remain authoritative. */
object ModelTransitions {
    suspend fun <T> run(cleanup: Boolean = false, block: suspend () -> T): T {
        val generation = GlobalTaskCancellation.generation
        val execution = currentCoroutineContext()[NativeTaskExecution]
        fun checkCurrent() {
            if (!cleanup) {
                execution?.checkActive()
                check(AgentRuntimeGate.isEnabled() && generation == GlobalTaskCancellation.generation) {
                    "NeedsUser: model transition revoked"
                }
            }
        }
        val held = ProcessTaskResources.model.currentLease()
        if (held != null) {
            checkCurrent()
            return block()
        }
        val owner = execution?.context?.taskId ?: TaskId(UUID.randomUUID().toString())
        return ProcessTaskResources.model.withLease(owner, ::checkCurrent) { block() }
    }
}
