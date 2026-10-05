package com.unoone.agent.core.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fake adapter/clock only: these do not qualify real Android accessibility. */
class DeviceConfirmationRefreshTest {
    private val bounds = RectData(0, 0, 100, 100)
    private val target = UiNode("n", 1, "0", "example.app", "EditText", "example.app:id/name",
        text = "Name", bounds = bounds, editable = true, semantic = TargetSemantic.FORM_FIELD)
    private fun state(id: String, time: Long, node: UiNode = target, truncated: Boolean = false) =
        PerceptionState(UiSnapshot(id, time, time, bounds,
            listOf(UiWindow(1, node.packageName, bounds, listOf(node))), truncated))
    private val auth = DeviceAuthorization(true, setOf("example.app", "other.app"))
    private class Brain : UnoBrain {
        override val capabilities = BrainCapabilities(devicePlanning = true)
        override suspend fun plan(request: DevicePlanRequest) = DeviceAction.SetText(request.perception.snapshot.id, "n", "hello")
        override suspend fun chat(message: String) = ""
        override suspend fun interpretScreen(state: PerceptionState) = ScreenInterpretation("")
        override suspend fun groundTarget(description: String, state: PerceptionState) = TargetGrounding()
        override suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState) = OutcomeAdvice(false, "")
    }
    private inner class Fixture(val changed: UiNode = target, val cancelOnRefresh: Boolean = false,
        val disableOnRefresh: Boolean = false, val truncated: Boolean = false) {
        var now = 0L
        var enabled = true
        var observes = 0
        var dispatches = 0
        var prompts = 0
        val epochs = DeviceEpoch()
        var approval: ActionConfirmation? = null
        val adapter = object : DeviceAdapter {
            override suspend fun observe(): PerceptionState {
                observes++
                if (observes == 2) {
                    if (cancelOnRefresh) epochs.cancel()
                    if (disableOnRefresh) enabled = false
                }
                return if (observes == 1) state("original", 0) else state("fresh-$observes", now, changed, truncated)
            }
            override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
                guard.validate(action, state, now)
                assertEquals("fresh-2", action.snapshotRef())
                approval = guard.confirmation
                dispatches++
                return DeviceDispatch(true)
            }
            override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) = Unit
        }
        suspend fun run() = DeviceAgentLoop(Brain(), adapter, epochs, { enabled }, { now },
            DeviceConfirmationProvider { action, snapshot, epoch ->
                prompts++
                now = 6001 // Human review exceeds the 5-second snapshot freshness limit.
                ActionConfirmation(epoch, snapshot.id, DeviceActionCodec.digest(action))
            }).run("fill name", NativeGoalPredicate { dispatches > 0 }, auth)
    }
    @Test fun approvalAfterFiveSecondsRefreshesUnchangedExactAction() = runBlocking {
        val f = Fixture()
        assertEquals(DeviceOutcomeStatus.VERIFIED, f.run().status)
        assertEquals(1, f.dispatches)
        assertEquals(1, f.prompts)
        assertEquals("original", f.approval!!.originalSnapshotId)
        assertNotNull(f.approval!!.semanticActionIdentity)
        assertNotNull(f.approval!!.equivalenceStateHash)
    }
    @Test fun targetChangedBlocksWithoutRepeatedPrompts() = runBlocking {
        val f = Fixture(target.copy(text = "Different field"))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, f.run().status)
        assertEquals(0, f.dispatches)
        assertEquals(1, f.prompts)
    }
    @Test fun sameLabelDifferentAppOrResourceFails() = runBlocking {
        for (node in listOf(target.copy(packageName = "other.app"), target.copy(resourceId = "example.app:id/other"),
            target.copy(bounds = RectData(1, 1, 100, 100)), target.copy(semantic = TargetSemantic.UNKNOWN))) {
            val f = Fixture(node)
            assertEquals(DeviceOutcomeStatus.NEEDS_USER, f.run().status)
            assertEquals(0, f.dispatches)
            assertEquals(1, f.prompts)
        }
    }
    @Test fun cancelDuringReobserveBlocks() = runBlocking {
        val f = Fixture(cancelOnRefresh = true)
        try { f.run(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, f.dispatches)
    }
    @Test fun masterDisableDuringReobserveBlocks() = runBlocking {
        val f = Fixture(disableOnRefresh = true)
        try { f.run(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, f.dispatches)
    }
    @Test fun truncatedRefreshCannotProveEquivalence() = runBlocking {
        val f = Fixture(truncated = true)
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, f.run().status)
        assertEquals(0, f.dispatches)
    }
}
