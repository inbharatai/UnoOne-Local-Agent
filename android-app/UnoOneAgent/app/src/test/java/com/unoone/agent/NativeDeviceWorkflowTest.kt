package com.unoone.agent

import com.unoone.agent.core.device.*
import com.unoone.agent.task.admittedDeviceTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeDeviceWorkflowTest {
    private val gmail = "com.google.android.gm"
    private val chrome = "com.android.chrome"
    private val resolver: (String) -> String? = { when (it.lowercase()) { "gmail" -> gmail; "chrome" -> chrome; else -> null } }
    @Test fun quotedSequencePreservesPayload() {
        assertEquals(NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), NativeDeviceGoal.Find(gmail, "Madhav then Alice"), NativeDeviceGoal.OpenApp(chrome))),
            NativeDeviceCommands.parse("device: open Gmail then find \"Madhav then Alice\" in Gmail then open chrome", resolver))
    }
    @Test fun rejectsPartialAndInventedWorkflows() {
        listOf("device: open gmail then create event from that email", "device: find Madhav and send mail in Gmail", "device: find \"unclosed in Gmail", "device: open gmail then")
            .forEach { assertTrue(it, NativeDeviceCommands.parse(it, resolver) is NativeDeviceGoal.NeedsUser) }
    }
    private class Adapter(var pkg: String) : DeviceAdapter {
        var sequence = 0L
        var node: UiNode? = null
        val actions = mutableListOf<DeviceAction>()
        var afterExecute: () -> Unit = {}
        var beforeObserve: () -> Unit = {}
        var resultAfterEdit = false
        private var observationPackages = emptySet<String>()
        private var issued: PerceptionState? = null
        override fun withObservationPackages(packages: Set<String>): DeviceAdapter {
            observationPackages = packages.toSet()
            return this
        }
        override suspend fun observe(): PerceptionState {
            beforeObserve()
            val bounds = RectData(0, 0, 100, 100)
            return PerceptionState(UiSnapshot("s${sequence++}", 0, actions.size.toLong(), bounds, listOf(UiWindow(1, pkg, bounds, if (pkg in observationPackages) listOfNotNull(node) else emptyList())))).also { issued = it }
        }
        override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
            check(state === issued) { "Snapshot was not issued by this scoped adapter" }
            guard.validate(action, state, 0); actions += action
            if (action is DeviceAction.OpenApp) pkg = action.packageName
            if (action is DeviceAction.FocusNode) node = node?.copy(focused = true)
            if (action is DeviceAction.SetText) node = if (resultAfterEdit) node?.copy(text = action.text, editable = false,
                className = "android.widget.TextView", resourceId = "$pkg:id/result", semantic = TargetSemantic.UNKNOWN)
                else node?.copy(text = action.text)
            afterExecute()
            return DeviceDispatch(true)
        }
        override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) {}
    }
    @Test fun initialExplicitOpenFromMetadataOnlyIsAllowed() = admittedDeviceTest {
        val adapter = Adapter("com.unoone.agent")
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 })
            .run(NativeDeviceGoal.OpenApp(gmail))
        assertEquals(DeviceOutcomeStatus.VERIFIED, result.status)
        assertEquals(listOf(DeviceAction.OpenApp(gmail)), adapter.actions)
    }
    @Test fun userTasksBoardTakeOverAfterAppOpenNeverReopens() = admittedDeviceTest {
        val adapter = Adapter("com.unoone.agent")
        adapter.afterExecute = { adapter.pkg = "com.unoone.agent" }
        val result = DeviceAgentSession(brainProvider = { error("No model on takeover") },
            adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }).run(NativeDeviceGoal.OpenApp(gmail))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, result.status)
        assertEquals(listOf(DeviceAction.OpenApp(gmail)), adapter.actions)
    }
    @Test fun sequenceBoundaryHumanChangeBlocksNextOpen() = admittedDeviceTest {
        val adapter = Adapter("com.unoone.agent")
        adapter.beforeObserve = { if (adapter.sequence == 2L) adapter.pkg = "com.unoone.agent" }
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }).run(
            NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), NativeDeviceGoal.OpenApp(chrome))))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, result.status)
        assertEquals(listOf(DeviceAction.OpenApp(gmail)), adapter.actions)
    }
    @Test fun staleQueuedCurrentCannotAutoSwitch() = admittedDeviceTest {
        val adapter = Adapter(chrome)
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 },
            foregroundPackage = { gmail }).run(NativeDeviceGoal.Current("back"))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, result.status)
        assertTrue(adapter.actions.isEmpty())
    }
    @Test fun genericNativeGoalDoesNotExposeOutsideScopeToModel() = admittedDeviceTest {
        val adapter = Adapter("com.unoone.agent")
        val brain = object : UnoBrain by NativeOpenAppBrain(gmail) {
            override suspend fun plan(request: DevicePlanRequest): DeviceAction = error("Outside state reached model")
        }
        val result = DeviceAgentSession({ brain }, { adapter }, { true }, { 0 }).runNativeGoals("Open target",
            listOf(NativeGoals.foregroundPackage(gmail)), DeviceAuthorization(observe = true, allowedPackages = setOf(gmail)))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, result.status)
        assertTrue(adapter.actions.isEmpty())
    }
    @Test fun multiAppReceiptsAreSequentialNotConjunction() = admittedDeviceTest {
        val adapter = Adapter("com.android.settings")
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }).run(
            NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), NativeDeviceGoal.OpenApp(chrome))))
        assertEquals(DeviceOutcomeStatus.VERIFIED, result.status)
        assertEquals(2, result.steps)
        assertTrue(result.reason.contains("1:OpenApp@")); assertTrue(result.reason.contains("2:OpenApp@"))
    }
    @Test fun reachableFindInvokesInjectedBrainButRejectsInventedEdit() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        var calls = 0
        val brain = object : UnoBrain by NativeOpenAppBrain(gmail) {
            override suspend fun plan(request: DevicePlanRequest): DeviceAction { calls++; return DeviceAction.SetText(request.perception.snapshot.id, "unknown", "invented") }
        }
        val session = DeviceAgentSession({ brain }, { adapter }, { true }, { 0 })
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, session.run(NativeDeviceGoal.Find(gmail, "Madhav")).status)
        assertEquals(1, calls); assertTrue(adapter.actions.isEmpty())
        session.run(NativeDeviceGoal.Find(gmail, "Madhav"), useModelPlanner = false)
        assertEquals(1, calls)
    }
    @Test fun skillsGlobalStopAfterPlanCannotDispatch() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        val brain = object : UnoBrain by NativeOpenAppBrain(gmail) {
            override suspend fun plan(request: DevicePlanRequest): DeviceAction {
                // Exact entry point used by Skills Stop all, after suspended planning.
                com.unoone.agent.core.runtime.GlobalTaskCancellation.cancelAll()
                return DeviceAction.Back
            }
        }
        val session = DeviceAgentSession({ brain }, { adapter }, { true }, { 0 })
        try {
            session.run(NativeDeviceGoal.Find(gmail, "Alice"))
            fail("Global generation must revoke the session")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(adapter.actions.isEmpty())
    }

    @Test fun reviewedFindFocusesAndSetsOnlyExplicitTextWithApproval() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        adapter.resultAfterEdit = true
        adapter.node = UiNode("field", 1, "0", gmail, "android.widget.EditText", "$gmail:id/search_view", "",
            bounds = RectData(1, 1, 90, 20), editable = true, focusable = true, semantic = TargetSemantic.FORM_FIELD)
        var approvals = 0
        val session = DeviceAgentSession(brainProvider = { error("deterministic route must win") }, adapterProvider = { adapter },
            enabled = { true }, clockMs = { 0 }, confirmations = DeviceConfirmationProvider { action, snapshot, epoch ->
                approvals++; ActionConfirmation(epoch, snapshot.id, DeviceActionCodec.digest(action))
            })
        assertEquals(DeviceOutcomeStatus.VERIFIED, session.run(NativeDeviceGoal.Find(gmail, "Madhav")).status)
        assertEquals(0, approvals)
        assertTrue(adapter.actions[0] is DeviceAction.FocusNode)
        assertEquals("Madhav", (adapter.actions[1] as DeviceAction.SetText).text)
    }
    @Test fun cancellationCannotResetBetweenGoals() = admittedDeviceTest {
        val adapter = Adapter("com.android.settings")
        val session = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 })
        adapter.afterExecute = { session.cancel() }
        try {
            session.run(NativeDeviceGoal.Sequence(listOf(NativeDeviceGoal.OpenApp(gmail), NativeDeviceGoal.OpenApp(chrome))))
            fail("Stop must cancel entire sequence")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, adapter.actions.size)
    }
    @Test fun clickNeverMasqueradesAsFocus() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        adapter.node = UiNode("field", 1, "0", gmail, "android.widget.EditText", "$gmail:id/search_view", "Search",
            bounds = RectData(1, 1, 90, 20), editable = true, focusable = true, clickable = true, semantic = TargetSemantic.FORM_FIELD)
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }).run(NativeDeviceGoal.Click(gmail, "Search"))
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, result.status)
        assertTrue(adapter.actions.isEmpty())
    }
    @Test fun readScreenReturnsNativeTextWithoutModel() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        adapter.node = UiNode("label", 1, "0", gmail, "android.widget.TextView", text = "Visible source text", bounds = RectData(1, 1, 90, 20))
        val result = DeviceAgentSession(brainProvider = { error("No model needed") }, adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }, foregroundPackage = { gmail }).run(NativeDeviceGoal.Current("read"))
        assertEquals(DeviceOutcomeStatus.VERIFIED, result.status)
        assertTrue(result.reason.contains("Visible source text"))
        assertTrue(adapter.actions.isEmpty())
    }
    @Test fun editableQueryEchoIsNotFindSuccess() = admittedDeviceTest {
        val adapter = Adapter(gmail)
        adapter.node = UiNode("field", 1, "0", gmail, "android.widget.EditText", "$gmail:id/search_view", "Madhav",
            bounds = RectData(1, 1, 90, 20), editable = true, focusable = true, focused = true, semantic = TargetSemantic.FORM_FIELD)
        val result = DeviceAgentSession(adapterProvider = { adapter }, enabled = { true }, clockMs = { 0 }).run(NativeDeviceGoal.Find(gmail, "Madhav"))
        assertNotEquals(DeviceOutcomeStatus.VERIFIED, result.status)
        assertTrue(result.steps <= 12)
    }
}
