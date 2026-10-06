package com.unoone.agent.task

import com.unoone.agent.core.model.ExclusiveBrainLeaseState
import com.unoone.agent.core.task.ProcessTaskResources
import com.unoone.agent.core.task.TaskResourceArbiter

/** Captured by the exact transition owner BEFORE entering its NonCancellable cleanup.
 * Carries a live scheduling lease, not an arbitrary caller-supplied owner string.
 * It may cross the cleanup Job boundary but never outlive the scheduling lease.
 */
internal class PhoneModelRestoreAuthorization private constructor(
    private val lease: TaskResourceArbiter.Lease,
    internal val residentOwner: String,
    private val stopGeneration: Long
) {
    fun checkActive() {
        lease.checkActive()
        check(com.unoone.agent.core.runtime.AgentRuntimeGate.isEnabled())
        check(com.unoone.agent.core.runtime.GlobalTaskCancellation.generation == stopGeneration)
        check(ProcessTaskResources.model.owner() == lease.owner)
        check(ExclusiveBrainLeaseState.currentOwner() == residentOwner)
    }

    companion object {
        suspend fun capture(): PhoneModelRestoreAuthorization {
            val lease = requireNotNull(ProcessTaskResources.model.currentLease()) {
                "Phone restore requires the exact scheduling owner"
            }
            val owner = requireNotNull(ExclusiveBrainLeaseState.currentOwner()) {
                "Phone restore requires exclusive residency"
            }
            return PhoneModelRestoreAuthorization(lease, owner, com.unoone.agent.core.runtime.GlobalTaskCancellation.generation)
        }
    }
}
