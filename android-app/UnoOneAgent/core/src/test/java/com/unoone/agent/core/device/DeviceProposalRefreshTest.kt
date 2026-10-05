package com.unoone.agent.core.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Fake slow inference and monotonic clock; no real-device qualification implied. */
class DeviceProposalRefreshTest {
    private val bounds = RectData(0, 0, 100, 100)
    private val target = UiNode("n", 1, "0", "example.app", "EditText", "example.app:id/name",
        text = "Name", bounds = bounds, editable = true, semantic = TargetSemantic.FORM_FIELD)
    private fun state(id: String, time: Long, node: UiNode = target) = PerceptionState(
        UiSnapshot(id, time, time, bounds, listOf(UiWindow(1, node.packageName, bounds, listOf(node)))))
    private inner class Fixture(val changed: UiNode = target, val cancelAt: String = "", val disable: Boolean = false) {
        var now = 0L
        var enabled = true
        var observes = 0
        var plans = 0
        var dispatches = 0
        var prompts = 0
        val epochs = DeviceEpoch()
        fun stop(at: String) {
            if (cancelAt == at) { if (disable) enabled = false else epochs.cancel() }
        }
        val brain = object : UnoBrain {
            override val capabilities = BrainCapabilities(devicePlanning = true)
            override suspend fun plan(request: DevicePlanRequest): DeviceAction {
                plans++
                now += 20_000
                yield()
                stop("plan")
                return DeviceActionCodec.decodeProposal(
                    """{"type":"SetText","snapshotId":"${request.perception.snapshot.id}","nodeId":"n","text":"hello"}""",
                    request.perception)
            }
            override suspend fun chat(message: String) = ""
            override suspend fun interpretScreen(state: PerceptionState) = ScreenInterpretation("")
            override suspend fun groundTarget(description: String, state: PerceptionState) = TargetGrounding()
            override suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState) = OutcomeAdvice(false, "")
        }
        val adapter = object : DeviceAdapter {
            override suspend fun observe(): PerceptionState {
                observes++
                yield()
                if (observes == 2) stop("refresh")
                return state("s$observes", now, if (observes == 1) target else changed)
            }
            override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
                guard.validate(action, state, now)
                assertEquals("s3", action.snapshotRef()) // inference refresh, then confirmation refresh
                assertEquals(20_000L, state.snapshot.capturedAtMs)
                assertEquals("hello", (action as DeviceAction.SetText).text)
                assertTrue(DeviceSafetyPolicy.confirmationMatches(guard.confirmation, action, state.snapshot, guard.epoch))
                dispatches++
                return DeviceDispatch(true)
            }
            override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) = Unit
        }
        suspend fun run() = DeviceAgentLoop(brain, adapter, epochs, { enabled }, { now },
            DeviceConfirmationProvider { action, snapshot, epoch ->
                prompts++
                assertEquals("s2", snapshot.id)
                ActionConfirmation(epoch, snapshot.id, DeviceActionCodec.digest(action))
            }).run("fill name", NativeGoalPredicate { dispatches > 0 },
                DeviceAuthorization(true, setOf("example.app", "other.app")), DeviceBudget(retries = 0))
    }
    @Test fun slowTwentySecondStableProposalGetsFreshNativeSafetyAndConfirmation() = runBlocking {
        val f = Fixture()
        assertEquals(DeviceOutcomeStatus.VERIFIED, f.run().status)
        assertEquals(1, f.plans)
        assertEquals(1, f.dispatches)
        assertEquals(1, f.prompts)
    }
    @Test fun changedFieldPackageOrTargetNeverExecutesOriginalProposal() = runBlocking {
        for (node in listOf(target.copy(text = "Changed"), target.copy(packageName = "other.app"),
            target.copy(id = "different"), target.copy(resourceId = "example.app:id/other"),
            target.copy(bounds = RectData(1, 1, 100, 100)))) {
            val f = Fixture(node)
            assertEquals(DeviceOutcomeStatus.FAILED, f.run().status)
            assertEquals(1, f.plans) // bounded replan budget exhausted, no stale execution
            assertEquals(0, f.dispatches)
            assertEquals(0, f.prompts)
        }
    }
    @Test fun stopAndMasterDisableAfterInferenceOrRefreshPreventDispatch() = runBlocking {
        for (at in listOf("plan", "refresh")) for (disable in listOf(false, true)) {
            val f = Fixture(cancelAt = at, disable = disable)
            try { f.run(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertEquals(0, f.dispatches)
            assertEquals(0, f.prompts)
        }
    }
    @Test fun structuralDecodeDoesNotMakeExpiredActionExecutable() {
        val source = state("source", 0)
        val proposal = DeviceActionCodec.decodeProposal(
            """{"type":"SetText","snapshotId":"source","nodeId":"n","text":"hello"}""", source)
        try { DeviceActionValidator.validate(proposal, source, 20_000); fail("Stale action accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
