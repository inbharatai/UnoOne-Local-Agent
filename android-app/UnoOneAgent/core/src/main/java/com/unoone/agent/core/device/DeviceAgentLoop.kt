package com.unoone.agent.core.device

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Model advice cannot execute, authorize, or establish native completion. */
data class BrainCapabilities(val chat: Boolean = true, val devicePlanning: Boolean = false, val vision: Boolean = false)
data class DevicePlanRequest(val goal: String, val perception: PerceptionState, val context: String, val step: Int)
data class ScreenInterpretation(val description: String)
data class TargetGrounding(val nodeId: String? = null, val visualTargetId: String? = null)
data class OutcomeAdvice(val likelySatisfied: Boolean, val reason: String)
interface UnoBrain {
    val capabilities: BrainCapabilities
    suspend fun plan(request: DevicePlanRequest): DeviceAction
    suspend fun chat(message: String): String
    suspend fun interpretScreen(state: PerceptionState): ScreenInterpretation
    suspend fun groundTarget(description: String, state: PerceptionState): TargetGrounding
    suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState): OutcomeAdvice
}

/** Accepted means dispatch only, never task success. Failed/uncertain side effects are not replayed. */
data class DeviceDispatch(val accepted: Boolean, val detail: String = "")
interface DeviceAdapter {
    /** Optional source-level scope hook. Null means task observation must fail closed.
     * Implementations must filter BEFORE reading labels and retain native snapshot identity. */
    fun withObservationPackages(packages: Set<String>): DeviceAdapter? = null
    suspend fun observe(): PerceptionState
    suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch
    suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long)
}
class DeviceExecutionGuard(
    private val epochs: DeviceEpoch, val epoch: Long, private val enabled: () -> Boolean,
    val authorization: DeviceAuthorization, val confirmation: ActionConfirmation? = null
) {
    fun check() { epochs.check(epoch); if (!enabled()) throw kotlinx.coroutines.CancellationException("Master disabled") }
    /** Adds a denial-only resource check without changing epoch or bound confirmation identity. */
    fun withAdditionalCheck(extra: () -> Unit): DeviceExecutionGuard {
        val original = this
        return DeviceExecutionGuard(epochs, epoch, { original.check(); extra(); true }, authorization, confirmation)
    }
    fun validate(action: DeviceAction, state: PerceptionState, nowMs: Long) {
        check(); DeviceActionValidator.validate(action, state, nowMs)
        when (val decision = DeviceSafetyPolicy.decide(action, state, authorization)) {
            SafetyDecision.Allow -> Unit
            is SafetyDecision.Confirm -> require(DeviceSafetyPolicy.confirmationMatches(confirmation, action, state.snapshot, epoch)) { "Confirmation expired" }
            is SafetyDecision.Handover -> error(decision.reason)
        }
        check()
    }
}

/** This predicate is supplied by native caller, never parsed from model output. */
fun interface NativeGoalPredicate { fun satisfied(state: PerceptionState): Boolean }
object NativeGoals {
    fun foregroundPackage(packageName: String) = NativeGoalPredicate { state -> state.snapshot.windows.firstOrNull()?.packageName == packageName }
    fun fieldEquals(packageName: String, resourceId: String, expected: String) = NativeGoalPredicate { state ->
        state.snapshot.nodes.any { !it.password && it.packageName == packageName && it.resourceId == resourceId && it.editable && it.text == expected }
    }
}
enum class DeviceBudgetTier(val steps: Int) { QUICK(4), STANDARD(12), EXTENDED(32) }
data class DeviceBudget(val maxSteps: Int = 12, val deadlineMs: Long = 60_000, val retries: Int = 2, val noProgressLimit: Int = 3) {
    init { require(maxSteps in 1..32 && deadlineMs in 1..180_000 && retries in 0..2 && noProgressLimit in 1..3) }
    companion object { fun forTier(tier: DeviceBudgetTier) = DeviceBudget(maxSteps = tier.steps) }
}
enum class DeviceOutcomeStatus { VERIFIED, NEEDS_USER, FAILED, LIMIT_REACHED }
data class DeviceOutcome(val status: DeviceOutcomeStatus, val steps: Int, val reason: String, val finalSnapshotId: String? = null)
fun interface DeviceConfirmationProvider {
    suspend fun confirm(action: DeviceAction, snapshot: UiSnapshot, epoch: Long): ActionConfirmation?
}

class DeviceAgentLoop(
    private val brain: UnoBrain,
    private val adapter: DeviceAdapter,
    private val epochs: DeviceEpoch,
    private val enabled: () -> Boolean,
    private val clockMs: () -> Long, // Required: same monotonic domain as adapter capture.
    private val confirmations: DeviceConfirmationProvider = DeviceConfirmationProvider { _, _, _ -> null }
) {
    private val mutex = Mutex()
    suspend fun run(goal: String, predicate: NativeGoalPredicate, authorization: DeviceAuthorization,
        budget: DeviceBudget = DeviceBudget(), initialOpenAppPackage: String? = null): DeviceOutcome {
        val epoch = epochs.current() // Capture before waiting: Stop invalidates queued runs too.
        return mutex.withLock {
        require(goal.length in 1..4096)
        var steps = 0
        suspend fun check() { currentCoroutineContext().ensureActive(); epochs.check(epoch); if (!enabled()) throw kotlinx.coroutines.CancellationException("Master disabled") }
        check()
        if (!authorization.observe) return@withLock DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, 0, "Observation consent required")
        withTimeoutOrNull(budget.deadlineMs) {
            check(); var state = adapter.observe(); check()
            fun outside(s: PerceptionState) = s.snapshot.windows.firstOrNull()?.packageName !in authorization.allowedPackages
            fun takeover(s: PerceptionState) = DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps,
                "Foreground changed outside authorized scope; user control retained", s.snapshot.id)
            val initialLaunch = initialOpenAppPackage?.takeIf { it in authorization.allowedPackages }
            var noProgress = 0
            var proposalErrors = 0
            while (steps < budget.maxSteps) {
                check()
                try { DeviceActionValidator.validate(DeviceAction.Observe, state, clockMs()) }
                catch (_: IllegalArgumentException) { return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Stale observation cannot prove goal", state.snapshot.id) }
                if (outside(state) && !(steps == 0 && initialLaunch != null)) return@withTimeoutOrNull takeover(state)
                if (!outside(state) && predicate.satisfied(state)) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.VERIFIED, steps, "Native goal predicate satisfied", state.snapshot.id)
                if (!brain.capabilities.devicePlanning) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "Device planner unavailable", state.snapshot.id)
                var action = try {
                    val proposal = if (steps == 0 && initialLaunch != null &&
                        state.snapshot.windows.firstOrNull()?.packageName != initialLaunch) DeviceAction.OpenApp(initialLaunch)
                    else brain.plan(DevicePlanRequest(goal, state, DeviceContextCompactor.compact(state), steps))
                    check(); DeviceActionValidator.validateStructure(proposal, state)
                    // Inference is not execution. Once its source expires, only an exact native
                    // reobservation may rebind this original proposal; no model retargeting.
                    val now = clockMs()
                    val executable = if (now < state.snapshot.capturedAtMs ||
                        now - state.snapshot.capturedAtMs > DeviceActionValidator.MAX_SNAPSHOT_AGE_MS) {
                        check(); val fresh = adapter.observe(); check()
                        if (outside(fresh)) return@withTimeoutOrNull takeover(fresh)
                        val rebound = NativeProposalRefresh.refreshEquivalent(proposal, state, fresh, clockMs())
                        state = fresh
                        requireNotNull(rebound) { "Proposal UI changed" }
                    } else proposal
                    check(); DeviceActionValidator.validate(executable, state, clockMs()); executable
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (_: Exception) {
                    check()
                    if (++proposalErrors > budget.retries) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Invalid/stale proposal limit", state.snapshot.id)
                    state = adapter.observe(); check(); continue
                }
                when (action) {
                    is DeviceAction.Done -> return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "Model Done is not native goal evidence", state.snapshot.id)
                    is DeviceAction.Fail -> return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Planner reported failure", state.snapshot.id)
                    is DeviceAction.AskUser -> return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, action.question, state.snapshot.id)
                    is DeviceAction.RequestConfirmation, is DeviceAction.Escalate -> return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "User handover requested", state.snapshot.id)
                    else -> Unit
                }
                var approval: ActionConfirmation? = null
                when (val decision = DeviceSafetyPolicy.decide(action, state, authorization)) {
                    is SafetyDecision.Handover -> return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, decision.reason, state.snapshot.id)
                    is SafetyDecision.Confirm -> {
                        check(); approval = confirmations.confirm(action, state.snapshot, epoch); check()
                        if (!DeviceSafetyPolicy.confirmationMatches(approval, action, state.snapshot, epoch))
                            return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "Confirmation denied/expired", state.snapshot.id)
                        // Human review can exceed the snapshot TTL. Never dispatch its stale UI or
                        // carry approval onto a changed target. One fresh native proof, else handover.
                        val fresh = try { check(); adapter.observe().also { check() } }
                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) {
                            check()
                            return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "Unable to reobserve confirmed target", state.snapshot.id)
                        }
                        check()
                        if (outside(fresh)) return@withTimeoutOrNull takeover(fresh)
                        val refreshed = ActionConfirmation.refreshEquivalent(approval, action, state, fresh, epoch, authorization, clockMs())
                            ?: return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "Confirmed UI changed or cannot be matched; review again manually", fresh.snapshot.id)
                        action = refreshed.first
                        approval = refreshed.second
                        state = fresh
                        check()
                    }
                    SafetyDecision.Allow -> Unit
                }
                val guard = DeviceExecutionGuard(epochs, epoch, enabled, authorization, approval)
                try { guard.validate(action, state, clockMs()) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                } catch (_: Exception) { return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.NEEDS_USER, steps, "State/approval expired", state.snapshot.id) }
                check(); steps++
                val dispatch = try { adapter.execute(action, state, guard) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { check(); return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Dispatch error; not replayed", state.snapshot.id) }
                check()
                if (!dispatch.accepted) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Action rejected or dispatch uncertain; not replayed", state.snapshot.id)
                adapter.awaitSettled(state.snapshot.eventSequence, 1000); check()
                val after = adapter.observe(); check()
                if (outside(after)) return@withTimeoutOrNull takeover(after)
                try {
                    DeviceActionValidator.validate(DeviceAction.Observe, after, clockMs())
                    require(after.snapshot.capturedAtMs >= state.snapshot.capturedAtMs && after.snapshot.eventSequence >= state.snapshot.eventSequence)
                } catch (_: IllegalArgumentException) { return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.FAILED, steps, "Stale post-action observation", after.snapshot.id) }
                if (predicate.satisfied(after)) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.VERIFIED, steps, "Native goal predicate satisfied", after.snapshot.id)
                noProgress = if (UiDiff(state.snapshot, after.snapshot).hasChange) 0 else noProgress + 1
                state = after
                if (noProgress >= budget.noProgressLimit) return@withTimeoutOrNull DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, steps, "No-progress limit", state.snapshot.id)
            }
            DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, steps, "Step budget exhausted", state.snapshot.id)
        } ?: DeviceOutcome(DeviceOutcomeStatus.LIMIT_REACHED, steps, "Elapsed deadline")
        }
    }
}
