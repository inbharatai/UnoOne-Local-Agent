package com.unoone.agent

import android.os.SystemClock
import com.unoone.agent.core.task.TaskCapability
import com.unoone.agent.task.ResourceEffects
import com.unoone.agent.accessibilitycontrol.AndroidDeviceAdapter
import com.unoone.agent.accessibilitycontrol.UnoOneAccessibilityService
import com.unoone.agent.core.device.*
import com.unoone.agent.core.runtime.AgentRuntimeGate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One retained owner per orchestrator. No approvals are cached; unsupported edits hand over. */
class DeviceAgentSession(
    private val brainProvider: () -> UnoBrain? = { null },
    private val adapterProvider: () -> DeviceAdapter? = {
        UnoOneAccessibilityService.getInstance()?.let { AndroidDeviceAdapter(it, semantics = com.unoone.agent.accessibilitycontrol.NativeSemanticResolver(NativeReviewedTargets::semantic)) }
    },
    private val enabled: () -> Boolean = AgentRuntimeGate::isEnabled,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val confirmations: DeviceConfirmationProvider = DeviceConfirmationProvider { _, _, _ -> null },
    private val foregroundPackage: () -> String? = { UnoOneAccessibilityService.getInstance()?.currentPackage }
) {
    private val epochs = DeviceEpoch()
    private val mutex = Mutex()
    private val interactionSteps = java.util.concurrent.atomic.AtomicLong()
    fun cancel() { epochs.cancel() }

    suspend fun run(goal: NativeDeviceGoal, useModelPlanner: Boolean = true): DeviceOutcome {
        val queuedCurrentPackage = if (goal is NativeDeviceGoal.Current ||
            (goal is NativeDeviceGoal.Sequence && goal.goals.firstOrNull() is NativeDeviceGoal.Current)) foregroundPackage() else null
        val epoch = epochs.current()
        val globalGeneration = com.unoone.agent.core.runtime.GlobalTaskCancellation.generation
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            checkRun(epoch, globalGeneration)
            if (!enabled()) throw kotlinx.coroutines.CancellationException("Master disabled")
            val goals = if (goal is NativeDeviceGoal.Sequence) goal.goals.toList() else listOf(goal)
            if (goals.isEmpty() || goals.size > 24 || goals.any { it is NativeDeviceGoal.Sequence || it is NativeDeviceGoal.NeedsUser })
                return@withLock needsUser("Unsupported or ambiguous workflow; no steps executed")
            val execution = ResourceEffects.execution()
            if (TaskCapability.UI_READ !in execution.context.scope.capabilities)
                return@withLock needsUser("UI observation outside task scope")
            val adapter = ResourceEffects.adapter(adapterProvider() ?: return@withLock needsUser("Enable UnoOne Accessibility access, then retry."))
            val budget = DeviceBudget.forTier(if (goals.size > 1) DeviceBudgetTier.EXTENDED else DeviceBudgetTier.STANDARD)
            val started = clockMs()
            var steps = 0
            val receipts = mutableListOf<String>()
            var last: String? = null
            var verifiedSnapshot: UiSnapshot? = null
            for ((index, original) in goals.withIndex()) {
                checkRun(epoch, globalGeneration)
                val boundary = adapter.observe()
                val boundaryPackage = boundary.snapshot.windows.firstOrNull()?.packageName
                val prior = verifiedSnapshot
                if (prior != null && (prior.windows.firstOrNull()?.packageName != boundaryPackage ||
                    prior.eventSequence != boundary.snapshot.eventSequence || UiDiff(prior, boundary.snapshot).hasChange))
                    return@withLock needsUser("Screen changed since prior verified goal; user control retained")
                if (index == 0 && original is NativeDeviceGoal.Current && boundaryPackage != queuedCurrentPackage)
                    return@withLock needsUser("Queued current app changed; no automatic switch")
                var latest = boundary
                var firstObservation = true
                val goalAdapter = object : DeviceAdapter by adapter {
                    override suspend fun observe(): PerceptionState {
                        if (firstObservation) { firstObservation = false; return boundary }
                        return adapter.observe().also { latest = it }
                    }
                }
                val item = if (original is NativeDeviceGoal.Current) {
                    checkRun(epoch, globalGeneration)
                    val pkg = boundaryPackage
                        ?: return@withLock needsUser("No active app")
                    if (pkg !in execution.context.scope.packages) return@withLock needsUser("Current app outside task scope")
                    when (original.command) {
                        "read" -> NativeDeviceGoal.ReadScreen(pkg)
                        "back" -> NativeDeviceGoal.Back(pkg)
                        "down" -> NativeDeviceGoal.Scroll(pkg, ScrollDirection.FORWARD)
                        "up" -> NativeDeviceGoal.Scroll(pkg, ScrollDirection.BACKWARD)
                        "click" -> NativeDeviceGoal.Click(pkg, original.exactText)
                        else -> return@withLock needsUser("Unsupported current-app request")
                    }
                } else original
                currentCoroutineContext().ensureActive(); checkRun(epoch, globalGeneration)
                val remaining = minOf(budget.maxSteps, NativeGoalPolicy.TOTAL_MAX) - steps
                val time = budget.deadlineMs - (clockMs() - started)
                if (remaining <= 0 || time <= 0) return@withLock DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, steps,
                    "Workflow budget exhausted; verified receipts: $receipts", last)
                val result = runOne(item, useModelPlanner, goalAdapter, epoch, globalGeneration, budget.copy(maxSteps = remaining, deadlineMs = time))
                checkRun(epoch, globalGeneration)
                steps += result.steps; last = result.finalSnapshotId
                if (result.status != DeviceOutcomeStatus.VERIFIED) return@withLock result.copy(steps = steps,
                    reason = "Step ${index + 1}: ${result.reason}; verified receipts: $receipts")
                verifiedSnapshot = latest.snapshot
                if (goals.size == 1) return@withLock result
                receipts += "${index + 1}:${item.javaClass.simpleName}@${result.finalSnapshotId}"
            }
            DeviceOutcome(DeviceOutcomeStatus.VERIFIED, steps, "All sequential native goals verified; receipts: $receipts", last)
        }
    }

    private suspend fun runOne(goal: NativeDeviceGoal, useModelPlanner: Boolean, adapter: DeviceAdapter,
        epoch: Long, globalGeneration: Long, budget: DeviceBudget): DeviceOutcome {
            if (goal is NativeDeviceGoal.Interact) return runInteraction(goal, adapter, epoch, globalGeneration, budget)
            if (goal is NativeDeviceGoal.Click) return runInteraction(NativeDeviceGoal.Interact(
                ReviewedInteraction(NativeTargetSelector(goal.exactText), ReviewedOperation.CLICK), goal.packageName),
                adapter, epoch, globalGeneration, budget)
            val pkg = when (goal) {
                is NativeDeviceGoal.ReadScreen -> goal.packageName
                is NativeDeviceGoal.Back -> goal.packageName
                is NativeDeviceGoal.Scroll -> goal.packageName
                is NativeDeviceGoal.Current -> return needsUser("Unresolved current app")
                is NativeDeviceGoal.Interact -> return needsUser("Unresolved interaction")
                is NativeDeviceGoal.OpenApp -> goal.packageName
                is NativeDeviceGoal.Find -> goal.packageName
                is NativeDeviceGoal.Click -> goal.packageName
                is NativeDeviceGoal.SetField -> goal.packageName
                is NativeDeviceGoal.NeedsUser -> return needsUser(goal.reason)
                is NativeDeviceGoal.Sequence -> return needsUser("Nested sequence")
            }
            val execution = ResourceEffects.execution()
            if (pkg !in execution.context.scope.packages) return needsUser("Requested app outside task scope")
            var attempted = false
            var searchFocused = false
            var searchEdited = false
            var reading = ""
            var scrollPath: String? = null
            var beforeClick: UiSnapshot? = null
            val predicate = NativeGoalPredicate { state ->
                val foreground = state.snapshot.windows.firstOrNull()?.packageName == pkg
                if (goal is NativeDeviceGoal.Back) attempted && beforeClick?.let { it.id != state.snapshot.id && UiDiff(it, state.snapshot).hasChange } == true
                else foreground && when (goal) {
                    is NativeDeviceGoal.ReadScreen -> {
                        reading = SensitiveReadRedaction.readScreen(state.snapshot, pkg)
                        true
                    }
                    is NativeDeviceGoal.Scroll -> attempted && beforeClick?.let { before ->
                        val path = scrollPath
                        fun contents(snapshot: UiSnapshot) = snapshot.nodes.filter { it.packageName == pkg && path != null && it.path.startsWith("$path.") }
                            .map { listOf(it.resourceId, it.text, it.description, it.bounds) }
                        !state.snapshot.truncated && before.id != state.snapshot.id && contents(before).isNotEmpty() &&
                            contents(state.snapshot).isNotEmpty() && contents(before) != contents(state.snapshot)
                    } == true
                    is NativeDeviceGoal.OpenApp -> true
                    is NativeDeviceGoal.Find -> !state.snapshot.truncated && state.snapshot.nodes.count { it.packageName == pkg && !it.password && !it.editable && it.semantic != TargetSemantic.FORM_FIELD && !it.semantic.sensitiveObservation() && it.text == goal.exactText } == 1
                    is NativeDeviceGoal.SetField -> !state.snapshot.truncated && state.snapshot.nodes.filter {
                        it.packageName == pkg && it.resourceId == goal.resourceId
                    }.singleOrNull()?.let { NativeGoalPolicy.reviewedField(it, pkg) && it.text == goal.text } == true
                    is NativeDeviceGoal.Click -> false // Routed through the bound, before/after interaction path.
                    else -> false
                }
            }
            val nativeBrain = NativeOpenAppBrain(pkg)
            val planner = object : UnoBrain by nativeBrain {
                override suspend fun plan(request: DevicePlanRequest): DeviceAction {
                    if (goal is NativeDeviceGoal.OpenApp) return nativeBrain.plan(request)
                    val state = request.perception
                    if (state.snapshot.windows.firstOrNull()?.packageName != pkg) {
                        return DeviceAction.AskUser("Requested app not foreground; no automatic switch")
                    }
                    if (state.snapshot.truncated) return DeviceAction.AskUser("Incomplete observation; cannot establish unique target")
                    if (goal is NativeDeviceGoal.Back || goal is NativeDeviceGoal.Scroll) {
                        if (attempted) return DeviceAction.AskUser("Action did not produce observable native change; not replayed")
                        attempted = true; beforeClick = state.snapshot
                        if (goal is NativeDeviceGoal.Back) return DeviceAction.Back
                        val target = state.snapshot.nodes.filter { it.packageName == pkg && it.scrollable &&
                            it.resourceId == "android:id/list" && it.semantic == TargetSemantic.NAVIGATION &&
                            NativeReviewedTargets.semantic(pkg, it.resourceId, it.className) == TargetSemantic.NAVIGATION }.singleOrNull()
                            ?: return DeviceAction.AskUser("No unique reviewed scroll container")
                        scrollPath = target.path
                        return DeviceAction.Scroll(state.snapshot.id, target.id, (goal as NativeDeviceGoal.Scroll).direction)
                    }
                    if (goal is NativeDeviceGoal.Find) {
                        if (state.snapshot.nodes.count { !it.editable && it.packageName == pkg && it.text == goal.exactText } > 1)
                            return DeviceAction.AskUser("Multiple visible matches; choose the exact identity manually")
                        val fields = state.snapshot.nodes.filter { NativeGoalPolicy.reviewedField(it, pkg) }
                        if (fields.size > 1) return DeviceAction.AskUser("Ambiguous reviewed search field")
                        val field = fields.singleOrNull()
                        if (field != null) {
                            if (!field.focused && !searchFocused) {
                                searchFocused = true
                                return DeviceAction.FocusNode(state.snapshot.id, field.id)
                            }
                            if (!field.focused) return DeviceAction.AskUser("Search focus not verified")
                            if (!searchEdited && field.text != goal.exactText) {
                                searchEdited = true
                                return DeviceAction.SetText(state.snapshot.id, field.id, goal.exactText)
                            }
                            // Query echo in the editable field is NOT a result receipt.
                            return DeviceAction.Wait(350)
                        }
                        val buttons = state.snapshot.nodes.filter { NativeGoalPolicy.reviewedSearchButton(it, pkg) }
                        if (buttons.size == 1 && !attempted) {
                            attempted = true
                            return DeviceAction.ClickNode(state.snapshot.id, buttons.single().id)
                        }
                        if (!useModelPlanner) return DeviceAction.AskUser("Reviewed search field not visible")
                        val brain = brainProvider()?.takeIf { it.capabilities.devicePlanning }
                            ?: return DeviceAction.AskUser("Planner unavailable")
                        val proposal = ResourceEffects.model { brain.plan(request.copy(goal = "Navigate within $pkg to its search field for an exact native find. Do not enter text or choose recipients.")) }
                        checkRun(epoch, globalGeneration)
                        return NativeGoalPolicy.navigation(proposal, state, pkg)
                    }
                    if (attempted) return DeviceAction.AskUser("Native postcondition not satisfied; action not replayed")
                    val matches = state.snapshot.nodes.filter { n -> n.packageName == pkg && !n.password && when (goal) {
                        is NativeDeviceGoal.SetField -> n.resourceId == goal.resourceId
                        is NativeDeviceGoal.Click -> n.text == goal.exactText || n.description == goal.exactText
                        else -> false
                    } }
                    val node = matches.singleOrNull() ?: return DeviceAction.AskUser("Target missing or ambiguous")
                    if (goal is NativeDeviceGoal.Click && !NativeGoalPolicy.reviewedSearchButton(node, pkg))
                        return DeviceAction.AskUser("Only reviewed search-button clicks have a native postcondition; focus is not a click")
                    if (goal is NativeDeviceGoal.SetField && !NativeGoalPolicy.reviewedField(node, pkg))
                        return DeviceAction.AskUser("Target has no reviewed native semantics")
                    attempted = true
                    return when (goal) {
                        is NativeDeviceGoal.SetField -> DeviceAction.SetText(state.snapshot.id, node.id, goal.text)
                        is NativeDeviceGoal.Click -> {
                            beforeClick = state.snapshot
                            DeviceAction.ClickNode(state.snapshot.id, node.id)
                        }
                        else -> DeviceAction.AskUser("Unsupported goal")
                    }
                }
            }
            checkRun(epoch, globalGeneration)
            val result = DeviceAgentLoop(planner, adapter, epochs, { epochs.current() == epoch && com.unoone.agent.core.runtime.GlobalTaskCancellation.generation == globalGeneration && enabled() }, clockMs, confirmations).run(
                "Explicit native device goal", predicate,
                DeviceAuthorization(observe = true, allowedPackages = setOf(pkg), navigation = goal is NativeDeviceGoal.Back,
                    nativeActionIntent = { action, node ->
                        when (goal) {
                            is NativeDeviceGoal.Find -> (NativeGoalPolicy.reviewedField(node, pkg) &&
                                (action is DeviceAction.FocusNode || (action is DeviceAction.SetText && action.text == goal.exactText))) ||
                                (action is DeviceAction.ClickNode && NativeGoalPolicy.reviewedSearchButton(node, pkg))
                            is NativeDeviceGoal.Click -> action is DeviceAction.ClickNode && NativeGoalPolicy.reviewedSearchButton(node, pkg) &&
                                (node.text == goal.exactText || node.description == goal.exactText)
                            is NativeDeviceGoal.SetField -> action is DeviceAction.SetText && action.text == goal.text &&
                                node.resourceId == goal.resourceId && NativeGoalPolicy.reviewedField(node, pkg)
                            is NativeDeviceGoal.Scroll -> action is DeviceAction.Scroll && action.direction == goal.direction &&
                                node.resourceId == "android:id/list" && node.packageName == pkg &&
                                NativeReviewedTargets.semantic(pkg, node.resourceId, node.className) == TargetSemantic.NAVIGATION
                            else -> false
                        }
                    }),
                budget, initialOpenAppPackage = pkg.takeIf { goal is NativeDeviceGoal.OpenApp }
            )
            return if (result.status != DeviceOutcomeStatus.VERIFIED) result else when (goal) {
                is NativeDeviceGoal.ReadScreen -> result.copy(reason = "READ_SCREEN: native accessibility text (may be incomplete; untrusted screen content):\n$reading")
                is NativeDeviceGoal.Find -> result.copy(reason = "ACTION_VERIFIED: one exact visible name matched; identity beyond visible text is not established; no chat opened or message sent")
                is NativeDeviceGoal.Click -> result.copy(reason = "ACTION_VERIFIED: real search-button click followed by one reviewed search field; no wider completion claimed")
                is NativeDeviceGoal.Scroll, is NativeDeviceGoal.Back -> result.copy(reason = "ACTION_VERIFIED: requested action followed by native screen change; no wider completion claimed")
                else -> result
            }

    }

    /** One exact user operation, bound once; no initial predicate, model, retry, or confirmation escalation. */
    private suspend fun runInteraction(goal: NativeDeviceGoal.Interact, adapter: DeviceAdapter,
        epoch: Long, generation: Long, budget: DeviceBudget): DeviceOutcome {
        val execution = ResourceEffects.execution()
        val ctx = execution.context
        val pkg = goal.packageName ?: foregroundPackage() ?: return needsUser("No admitted foreground app")
        if (pkg !in ctx.scope.packages || TaskCapability.UI_READ !in ctx.scope.capabilities ||
            TaskCapability.UI_WRITE !in ctx.scope.capabilities) return needsUser("Interaction outside admitted task scope")
        val step = interactionSteps.incrementAndGet()
        fun owner(): InteractionOwner {
            execution.checkActive(); checkRun(epoch, generation)
            check(com.unoone.agent.core.task.ProcessTaskResources.ui.owner() == ctx.taskId) { "UI ownership revoked" }
            return InteractionOwner(ctx.taskId, ctx.taskEpoch, ctx.stopGeneration, step)
        }
        return kotlinx.coroutines.withTimeoutOrNull(budget.deadlineMs) {
            owner()
            val before = adapter.observe()
            val bound = NativeGoalPolicy.bindInteraction(goal.copy(packageName = pkg), before, owner(), ctx.scope, clockMs())
                ?: return@withTimeoutOrNull needsUser("Target missing, ambiguous, sensitive, unknown, or outside reviewed scope")
            val guard = DeviceExecutionGuard(epochs, epoch, { owner(); true }, bound.authorization(ctx.scope) { owner() })
            guard.validate(bound.action, before, clockMs())
            // ResourceEffects alone charges and durably journals this one dispatch. Never charge twice here.
            val dispatch = adapter.execute(bound.action, before, guard)
            owner()
            if (!dispatch.accepted) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, 1,
                "Dispatch not accepted; not replayed", before.snapshot.id)
            adapter.awaitSettled(before.snapshot.eventSequence, minOf(1_500L, budget.deadlineMs))
            owner()
            val after = adapter.observe()
            val verified = bound.verified(after, owner(), clockMs())
            DeviceOutcome(if (verified) DeviceOutcomeStatus.VERIFIED else DeviceOutcomeStatus.NEEDS_USER, 1,
                if (verified) "ACTION_VERIFIED: exact native interaction produced its bounded observed effect; no send or wider completion claimed"
                else "Interaction effect unverified; not replayed", after.snapshot.id)
        } ?: DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, 1, "Interaction deadline reached; no replay")
    }

    /** Native callers only: predicates and authority are never model output. Every action is replanned from fresh observation. */
    suspend fun runNativeGoals(description: String, predicates: List<NativeGoalPredicate>, authorization: DeviceAuthorization,
        extended: Boolean = false): DeviceOutcome {
        require(predicates.isNotEmpty() && predicates.size <= 24)
        val epoch = epochs.current()
        val globalGeneration = com.unoone.agent.core.runtime.GlobalTaskCancellation.generation
        return mutex.withLock {
            checkRun(epoch, globalGeneration)
            if (!enabled()) throw kotlinx.coroutines.CancellationException("Master disabled")
            val execution = ResourceEffects.execution()
            if (TaskCapability.UI_READ !in execution.context.scope.capabilities ||
                !execution.context.scope.packages.containsAll(authorization.allowedPackages))
                return@withLock needsUser("Native observation outside admitted task scope")
            val rawBrain = brainProvider() ?: return@withLock needsUser("Planner unavailable")
            val brain = object : UnoBrain by rawBrain {
                override suspend fun plan(request: DevicePlanRequest): DeviceAction = ResourceEffects.model { rawBrain.plan(request) }
            }
            val adapter = ResourceEffects.adapter(adapterProvider() ?: return@withLock needsUser("Accessibility unavailable"))
            val budget = DeviceBudget.forTier(if (extended) DeviceBudgetTier.EXTENDED else DeviceBudgetTier.STANDARD)
            val started = clockMs()
            var steps = 0
            val receipts = mutableListOf<String>()
            var last: String? = null
            for ((index, predicate) in predicates.withIndex()) {
                currentCoroutineContext().ensureActive(); checkRun(epoch, globalGeneration)
                val remaining = minOf(budget.maxSteps, NativeGoalPolicy.TOTAL_MAX) - steps
                val time = budget.deadlineMs - (clockMs() - started)
                if (remaining <= 0 || time <= 0) return@withLock DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, steps, "Workflow budget exhausted; receipts: $receipts", last)
                val result = DeviceAgentLoop(brain, adapter, epochs, { epochs.current() == epoch && com.unoone.agent.core.runtime.GlobalTaskCancellation.generation == globalGeneration && enabled() }, clockMs, confirmations).run(
                    description, predicate, authorization, budget.copy(maxSteps = remaining, deadlineMs = time))
                checkRun(epoch, globalGeneration)
                steps += result.steps; last = result.finalSnapshotId
                if (result.status != DeviceOutcomeStatus.VERIFIED) return@withLock result.copy(steps = steps, reason = "${result.reason}; receipts: $receipts")
                receipts += "${index + 1}@${result.finalSnapshotId}"
            }
            DeviceOutcome(DeviceOutcomeStatus.VERIFIED, steps, "All sequential native predicates verified; receipts: $receipts", last)
        }
    }

    private fun checkRun(epoch: Long, globalGeneration: Long) {
        epochs.check(epoch)
        if (!enabled() || com.unoone.agent.core.runtime.GlobalTaskCancellation.generation != globalGeneration)
            throw kotlinx.coroutines.CancellationException("Global device stop")
    }

    private fun needsUser(reason: String) = DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, 0, reason)
}
