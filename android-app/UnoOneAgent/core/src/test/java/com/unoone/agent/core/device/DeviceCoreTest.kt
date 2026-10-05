package com.unoone.agent.core.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Deliberately fake device/model: these tests do not qualify Android accessibility or real inference. */
class DeviceCoreTest {
    private val bounds = RectData(0, 0, 100, 100)
    private fun node(text: String = "Search", semantic: TargetSemantic = TargetSemantic.NAVIGATION) =
        UiNode("n", 1, "0", "example.app", "Button", text = text, bounds = bounds, clickable = true, editable = true, semantic = semantic)
    private fun state(id: String = "s", text: String = "Search", time: Long = 0, semantic: TargetSemantic = TargetSemantic.NAVIGATION) =
        PerceptionState(UiSnapshot(id, time, 1, bounds, listOf(UiWindow(1, "example.app", bounds, listOf(node(if (semantic.sensitiveObservation()) "" else text, semantic))))))
    private val auth = DeviceAuthorization(true, setOf("example.app"), navigation = true)
    private class FakeBrain(val proposal: suspend (DevicePlanRequest) -> DeviceAction) : UnoBrain {
        override val capabilities = BrainCapabilities(devicePlanning = true)
        override suspend fun plan(request: DevicePlanRequest) = proposal(request)
        override suspend fun chat(message: String) = "fake"
        override suspend fun interpretScreen(state: PerceptionState) = ScreenInterpretation("fake")
        override suspend fun groundTarget(description: String, state: PerceptionState) = TargetGrounding()
        override suspend fun verifyOutcome(goal: String, before: PerceptionState, after: PerceptionState) = OutcomeAdvice(true, "untrusted fake claim")
    }
    private class FakeAdapter(var state: PerceptionState) : DeviceAdapter {
        var calls = 0
        var onExecute: (() -> Unit)? = null
        override suspend fun observe() = state
        override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
            guard.check(); calls++; onExecute?.invoke(); return DeviceDispatch(true)
        }
        override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) = Unit
    }
    @Test fun immutableCollectionsAndPasswordInvariant() {
        val list = mutableListOf(node())
        val window = UiWindow(1, "example.app", bounds, list)
        list.clear(); assertEquals(1, window.nodes.size)
        assertThrows(UnsupportedOperationException::class.java) { (window.nodes as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { node().copy(password = true) }
        assertThrows(IllegalArgumentException::class.java) { RectData(-1, 0, 2, 2) }
    }
    @Test fun hashIgnoresCaptureIdentityButDiffTracksText() {
        assertEquals(UiStateHasher.hash(state().snapshot), UiStateHasher.hash(state("new", time = 1).snapshot))
        assertFalse(UiDiff(state().snapshot, state("new").snapshot).hasChange)
        assertEquals(setOf("n"), UiDiff(state().snapshot, state(text = "Other").snapshot).changed)
    }
    @Test fun strictDecoderRejectsUnknownActionsKeysStaleAndBounds() {
        assertThrows(Exception::class.java) { DeviceActionCodec.decode("{\"type\":\"DeleteEverything\"}", state(), 0) }
        assertThrows(Exception::class.java) { DeviceActionCodec.decode("{\"type\":\"Home\",\"extra\":true}", state(), 0) }
        assertThrows(Exception::class.java) { DeviceActionCodec.decode("{\"type\":\"ClickNode\",\"snapshotId\":\"wrong\",\"nodeId\":\"n\"}", state(), 0) }
        assertThrows(Exception::class.java) { DeviceActionCodec.decode("{\"type\":\"ClickNode\",\"snapshotId\":\"s\",\"nodeId\":\"missing\"}", state(), 0) }
        assertThrows(Exception::class.java) { DeviceActionCodec.decode("{\"type\":\"Home\"}", state(), 6000) }
        assertThrows(Exception::class.java) { DeviceActionValidator.validate(DeviceAction.Swipe("s", 0, 0, 200, 200), state(), 0) }
        assertEquals(DeviceAction.Home, DeviceActionCodec.decode(DeviceActionCodec.encode(DeviceAction.Home), state(), 0))
    }
    @Test fun semanticProofAloneCannotAuthorizeArbitraryModelTarget() {
        val click = DeviceAction.ClickNode("s", "n")
        assertTrue(DeviceSafetyPolicy.decide(click, state(), auth) is SafetyDecision.Handover)
        val exact = DeviceAuthorization(true, setOf("example.app"), nativeActionIntent = { action, node -> action == click && node.id == "n" })
        assertEquals(SafetyDecision.Allow, DeviceSafetyPolicy.decide(click, state(), exact))
        for (semantic in listOf(TargetSemantic.UNKNOWN, TargetSemantic.SECRET, TargetSemantic.OTP, TargetSemantic.PAYMENT,
            TargetSemantic.LEGAL, TargetSemantic.CAPTCHA, TargetSemantic.FINAL_SEND, TargetSemantic.DESTRUCTIVE))
            assertTrue(DeviceSafetyPolicy.decide(click, state(semantic = semantic), exact) is SafetyDecision.Handover)
    }
    @Test fun policyIsFailClosedAndApprovalsAreBound() {
        for (semantic in listOf(TargetSemantic.UNKNOWN, TargetSemantic.SECRET, TargetSemantic.PAYMENT, TargetSemantic.OTP,
            TargetSemantic.CAPTCHA, TargetSemantic.LEGAL, TargetSemantic.FINAL_SEND))
            assertTrue(DeviceSafetyPolicy.decide(DeviceAction.ClickNode("s", "n"), state(semantic = semantic), auth) is SafetyDecision.Handover)
        val edit = DeviceAction.SetText("s", "n", "hello")
        val s = state(semantic = TargetSemantic.FORM_FIELD)
        assertTrue(DeviceSafetyPolicy.decide(edit, s, auth) is SafetyDecision.Confirm)
        val confirmation = ActionConfirmation(1, "s", DeviceActionCodec.digest(edit))
        assertTrue(DeviceSafetyPolicy.confirmationMatches(confirmation, edit, s.snapshot, 1))
        assertFalse(DeviceSafetyPolicy.confirmationMatches(confirmation, edit.copy(text = "different"), s.snapshot, 1))
        assertFalse(DeviceSafetyPolicy.confirmationMatches(confirmation, edit, s.snapshot, 2))
        assertTrue(DeviceSafetyPolicy.decide(DeviceAction.Swipe("s", 1, 1, 2, 2), s, auth) is SafetyDecision.Handover)
    }
    @Test fun compactionBoundedAndOcrCannotGrantSemanticProof() {
        assertTrue(DeviceContextCompactor.compact(state(), 256).length <= 256)
        assertTrue(DeviceContextCompactor.compact(state()).startsWith("UNTRUSTED"))
        val fused = PerceptionFusion.fuse(state().snapshot, listOf(OcrRegion("tap me", bounds, .9f)))
        assertNull(fused.visualTargets.single().semanticNodeId)
        assertTrue(DeviceSafetyPolicy.decide(DeviceAction.ClickVisualTarget("s", "ocr:0"), fused, auth) is SafetyDecision.Handover)
    }
    @Test fun doneAndAnyChangeAreNotSuccess() = runBlocking {
        val adapter = FakeAdapter(state())
        val done = DeviceAgentLoop(FakeBrain { DeviceAction.Done("success") }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, done.run("goal", NativeGoalPredicate { false }, auth).status)
        adapter.onExecute = { adapter.state = state(text = "changed ${adapter.calls}") }
        val loop = DeviceAgentLoop(FakeBrain { DeviceAction.Home }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.LIMIT_REACHED, loop.run("goal", NativeGoalPredicate { false }, auth, DeviceBudget(maxSteps = 4)).status)
        assertEquals(4, adapter.calls)
    }
    @Test fun noProgressAndRetriesAreBounded() = runBlocking {
        val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { DeviceAction.Home }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(3, loop.run("goal", NativeGoalPredicate { false }, auth).steps)
        var proposals = 0
        val invalid = DeviceAgentLoop(FakeBrain { proposals++; DeviceAction.ClickNode("wrong", "n") }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.FAILED, invalid.run("goal", NativeGoalPredicate { false }, auth).status)
        assertEquals(3, proposals)
        assertThrows(IllegalArgumentException::class.java) { DeviceBudget(maxSteps = 33) }
        assertEquals(listOf(4, 12, 32), DeviceBudgetTier.values().map { DeviceBudget.forTier(it).maxSteps })
    }
    @Test fun cancellationAfterModelSuspendPreventsSideEffect() = runBlocking {
        val epoch = DeviceEpoch(); val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { epoch.cancel(); DeviceAction.Home }, adapter, epoch, { true }, { 0 })
        try { loop.run("goal", NativeGoalPredicate { false }, auth); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, adapter.calls)
    }
    @Test fun cancellationAfterConfirmationPreventsSideEffect() = runBlocking {
        val epoch = DeviceEpoch(); val adapter = FakeAdapter(state(semantic = TargetSemantic.FORM_FIELD))
        val loop = DeviceAgentLoop(FakeBrain { DeviceAction.SetText("s", "n", "hello") }, adapter, epoch, { true }, { 0 },
            DeviceConfirmationProvider { action, snapshot, old -> epoch.cancel(); ActionConfirmation(old, snapshot.id, DeviceActionCodec.digest(action)) })
        try { loop.run("goal", NativeGoalPredicate { false }, auth); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, adapter.calls)
    }
    @Test fun staleObservationCannotClaimSuccess() = runBlocking {
        val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { DeviceAction.Done() }, adapter, DeviceEpoch(), { true }, { 6000 })
        assertEquals(DeviceOutcomeStatus.FAILED, loop.run("goal", NativeGoalPredicate { true }, auth).status)
    }
    @Test fun deadlineCancelsSuspendedPlanner() = runBlocking {
        val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { kotlinx.coroutines.delay(5000); DeviceAction.Home }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.LIMIT_REACHED, loop.run("goal", NativeGoalPredicate { false }, auth, DeviceBudget(deadlineMs = 50)).status)
        assertEquals(0, adapter.calls)
    }
    @Test fun masterDisableAfterPlanningStopsDispatch() = runBlocking {
        var enabled = true
        val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { enabled = false; DeviceAction.Home }, adapter, DeviceEpoch(), { enabled }, { 0 })
        try { loop.run("goal", NativeGoalPredicate { false }, auth); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, adapter.calls)
    }
    @Test fun onlyNativePredicateCompletes() = runBlocking {
        val adapter = FakeAdapter(state())
        val loop = DeviceAgentLoop(FakeBrain { error("Should not plan") }, adapter, DeviceEpoch(), { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.VERIFIED, loop.run("open app", NativeGoals.foregroundPackage("example.app"), auth).status)
        assertEquals(0, adapter.calls)
    }
    @Test fun sensitiveSemanticTextRejectedAndOcrExcluded() {
        for (semantic in TargetSemantic.values().filter { it.sensitiveObservation() }) {
            assertThrows(IllegalArgumentException::class.java) { node("123456", semantic) }
            val secret = state(semantic = semantic)
            val withOcr = PerceptionState(secret.snapshot, listOf(OcrRegion("123456", bounds, 1f)))
            assertFalse(DeviceContextCompactor.compact(withOcr).contains("123456"))
        }
    }
    @Test fun injectedElapsedClockHandlesSuspendOffset() = runBlocking {
        val adapter = FakeAdapter(state(time = 900000))
        val loop = DeviceAgentLoop(FakeBrain { error("not needed") }, adapter, DeviceEpoch(), { true }, { 900001 })
        assertEquals(DeviceOutcomeStatus.VERIFIED, loop.run("goal", NativeGoalPredicate { true }, auth).status)
    }
}
