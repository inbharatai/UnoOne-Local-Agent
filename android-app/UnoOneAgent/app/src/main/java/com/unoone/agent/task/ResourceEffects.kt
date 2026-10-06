package com.unoone.agent.task

import android.content.Context
import com.unoone.agent.core.device.*
import com.unoone.agent.core.task.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** App-only callback bridge: libraries receive callbacks, never depend on app context types. */
object ResourceEffects {
    /** Independent native producers use the same verified, bounded WAL as coordinator tasks. */
    fun record(context: Context, owner: TaskId, producer: TaskSource) {
        TaskJournalStore.shared(context).recordExternalProducer(owner, producer)
    }
    suspend fun execution(): NativeTaskExecution {
        currentCoroutineContext().ensureActive()
        val execution = requireNotNull(currentCoroutineContext()[NativeTaskExecution]) { "Native task context required" }
        execution.checkActive()
        check(ProcessTaskResources.ui.owner() == execution.context.taskId) { "UI lease not owned by task" }
        return execution
    }
    fun adapter(delegate: DeviceAdapter, packages: Set<String>, check: () -> Unit, beforeEffect: () -> Unit,
        beforeRead: () -> Unit = {}): DeviceAdapter {
        val admittedPackages = packages.toSet()
        check()
        val scoped = requireNotNull(delegate.withObservationPackages(admittedPackages)) {
            "Adapter cannot enforce source-level observation scope"
        }
        return object : DeviceAdapter by scoped {
            override suspend fun observe(): PerceptionState {
                currentCoroutineContext().ensureActive(); check(); beforeRead(); check()
                return scoped.observe().also { check(); currentCoroutineContext().ensureActive() }
            }
            override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
                currentCoroutineContext().ensureActive(); check()
                require(admittedPackages.containsAll(guard.authorization.allowedPackages)) { "Task package scope exceeded" }
                if (action is DeviceAction.OpenApp) require(action.packageName in admittedPackages) { "App outside task scope" }
                // observe() already paid for this snapshot; read dispatches do not mutate UI.
                when (action) {
                    DeviceAction.Observe, DeviceAction.ReadScreen, is DeviceAction.ReadNode, is DeviceAction.Wait -> Unit
                    else -> beforeEffect()
                }
                check()
                val wrapped = guard.withAdditionalCheck(check)
                // Same scoped native adapter owns both capture and execution identity.
                return scoped.execute(action, state, wrapped)
            }
        }
    }
    suspend fun adapter(delegate: DeviceAdapter): DeviceAdapter {
        val execution = execution()
        return adapter(delegate, execution.context.scope.packages, {
            execution.checkActive()
            check(ProcessTaskResources.ui.owner() == execution.context.taskId) { "UI ownership revoked" }
        }, { execution.beforeEffect(TaskCapability.UI_WRITE) }, {
            execution.context.beforeAction(TaskCapability.UI_READ)
        })
    }
    suspend fun <T> model(block: suspend () -> T): T {
        val execution = execution()
        check(!com.unoone.agent.core.model.ExclusiveBrainLeaseState.isActive()) { "NeedsUser: release browser/BlindAid model session before phone planning" }
        return ProcessTaskResources.model.withLease(execution.context.taskId, execution::checkActive) {
            check(!com.unoone.agent.core.model.ExclusiveBrainLeaseState.isActive()) { "NeedsUser: release browser/BlindAid model session before phone planning" }
            execution.context.beforeModelCall(); block()
        }
    }
}
