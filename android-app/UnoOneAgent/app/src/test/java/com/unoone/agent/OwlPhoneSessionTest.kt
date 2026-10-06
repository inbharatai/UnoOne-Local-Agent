package com.unoone.agent

import com.unoone.agent.core.device.*
import com.unoone.agent.core.task.*
import com.unoone.agent.owl.*
import com.unoone.agent.task.NativeTaskFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UNIT fixtures only: actual session/admission/resources, fake pixels/model/device. Not device or model qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwlPhoneSessionTest {
    private var pkg = "example.app"
    private val bounds = RectData(0, 0, 1000, 2000)
    private fun output(args: String) = "Action: Review.\n<tool_call>{\"name\":\"mobile_use\",\"arguments\":$args}</tool_call>"
    private val click = output("{\"action\":\"click\",\"coordinate\":[200,150]}")
    private inner class Rig : DeviceAdapter, OwlCapturePort {
        var now = 100L
        var sequence = 7L
        var serial = 0
        var captures = 0
        var models = 0
        var windowsSafe = true
        var noop = false
        var afterModel: () -> Unit = {}
        var raw = click
        var node = UiNode("button", 1, "0.1", pkg, "android.widget.Button", text = "Search",
            bounds = RectData(100, 200, 300, 400), clickable = true, semantic = TargetSemantic.NAVIGATION)
        val bytes = byteArrayOf(1, 2, 3)
        val actions = mutableListOf<DeviceAction>()
        var last: PerceptionState? = null
        override suspend fun observe() = PerceptionState(UiSnapshot("s${serial++}", now, sequence, bounds,
            listOf(UiWindow(1, node.packageName, bounds, listOf(node))))).also { last = it }
        override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
            assertSame(last, state)
            guard.validate(action, state, now)
            actions += action
            if (action is DeviceAction.OpenApp) node = node.copy(packageName = action.packageName)
            else if (!noop) { node = node.copy(selected = true); sequence++ }
            return DeviceDispatch(true)
        }
        override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) {}
        override fun hasPermission() = true
        override suspend fun capture(): OwlCapturedImage { captures++; return OwlCapturedImage(bytes, 250, 500, bounds, 0, now) }
        suspend fun run(f: NativeTaskFixture, goal: NativeDeviceGoal = NativeDeviceGoal.Click(pkg, "Search")): DeviceOutcome =
            f.run(TaskScope(setOf(TaskCapability.UI_READ, TaskCapability.UI_WRITE, TaskCapability.MODEL), packages = setOf(pkg))) { ctx ->
                // This unit fixture has fake device nodes/pixels and no packaged resources.
                // Supply only the expected result label; actual Android resources are covered
                // by the opt-in practice instrumentation suite, not claimed by this test.
                val resources = org.mockito.Mockito.mock(android.content.res.Resources::class.java)
                org.mockito.Mockito.`when`(resources.getString(com.unoone.agent.R.string.owl_practice_results))
                    .thenReturn("Search complete")
                val sessionContext = object : android.content.ContextWrapper(f.context) {
                    override fun getResources() = resources
                }
                OwlPhoneSession(sessionContext, this, { _, _, _ -> models++; afterModel(); raw }, ctx::checkActive,
                    { now }, this, { windowsSafe }, { 0 }).run(OwlTaskConsent(pkg, "Click Search", 2, 60), listOf(goal))
            }
        fun counts(c: Int, m: Int, d: Int) { assertEquals(c, captures); assertEquals(m, models); assertEquals(d, actions.size) }
    }
    private fun test(block: suspend (NativeTaskFixture) -> Unit) = runBlocking { NativeTaskFixture().use { block(it) } }
    @Test fun wrongPackageNeverCaptures() = test { f ->
        val r = Rig(); r.node = r.node.copy(packageName = "other.app")
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(0, 0, 0)
    }
    @Test fun overlayNeverCaptures() = test { f ->
        val r = Rig(); r.windowsSafe = false
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(0, 0, 0)
    }
    @Test fun secretNeverCaptures() = test { f ->
        val r = Rig(); r.node = r.node.copy(password = true, text = "", description = "", hint = "", semantic = TargetSemantic.SECRET)
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(0, 0, 0)
    }
    @Test fun changedUiAfterSlowModelNeverDispatches() = test { f ->
        val r = Rig(); r.afterModel = { r.now += 30_000; r.node = r.node.copy(text = "Changed") }
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(1, 1, 0); assertArrayEquals(ByteArray(3), r.bytes)
    }
    @Test fun equivalentUiAfterThirtySecondsRebindsAndDispatchesOnce() = test { f ->
        val r = Rig(); r.afterModel = { r.now += 30_000 }
        assertEquals(DeviceOutcomeStatus.VERIFIED, r.run(f).status); r.counts(1, 1, 1)
        assertEquals(DeviceAction.ClickNode("s3", "button"), r.actions.single()); assertArrayEquals(ByteArray(3), r.bytes)
    }
    @Test fun cancellationAfterModelUsesActualCoordinatorOwner() = test { f ->
        val r = Rig(); r.afterModel = { f.coordinator.close() }
        try { r.run(f); fail("Revoked owner must cancel") } catch (_: CancellationException) { }
        r.counts(1, 1, 0); assertArrayEquals(ByteArray(3), r.bytes)
    }
    @Test fun noOpIsNeedsUserNotSuccess() = test { f ->
        val r = Rig(); r.noop = true
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(1, 1, 1)
    }
    @Test fun ownPracticeSelectionChangeWithoutNewResultIsNotCompletion() = test { f ->
        pkg = f.context.packageName
        val r = Rig()
        r.node = r.node.copy(resourceId = "$pkg:id/owl_practice_search_button", className = "android.widget.TextView")
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status)
        r.counts(1, 1, 1)
        assertTrue("Fixture dispatch changed selection, but produced no result", r.node.selected)
    }
    @Test fun ownPracticeIdsRequireExactPackageAndClass() {
        val own = "com.unoone.agent"
        assertEquals(TargetSemantic.FORM_FIELD, NativeReviewedTargets.semantic(own, "$own:id/owl_practice_search_field", "android.widget.EditText"))
        assertEquals(TargetSemantic.NAVIGATION, NativeReviewedTargets.semantic(own, "$own:id/owl_practice_search_button", "android.widget.TextView"))
        assertEquals(TargetSemantic.UNKNOWN, NativeReviewedTargets.semantic("other.app", "$own:id/owl_practice_search_button", "android.widget.TextView"))
        assertEquals(TargetSemantic.UNKNOWN, NativeReviewedTargets.semantic(own, "$own:id/owl_practice_search_button", "android.widget.Button"))
    }
    @Test fun modelDoneIsNotCompletion() = test { f ->
        val r = Rig(); r.raw = output("{\"action\":\"terminate\",\"status\":\"success\"}")
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f).status); r.counts(1, 1, 0)
    }
    @Test fun wrongTextNeverDispatches() = test { f ->
        val r = Rig(); r.node = r.node.copy(className = "android.widget.EditText", editable = true, focused = true,
            focusable = true, semantic = TargetSemantic.FORM_FIELD)
        r.raw = output("{\"action\":\"type\",\"text\":\"unapproved\"}")
        val goal = NativeDeviceGoal.Interact(ReviewedInteraction(NativeTargetSelector("Search"), ReviewedOperation.WRITE, "approved"), pkg)
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, r.run(f, goal).status); r.counts(1, 1, 0)
    }
    @Test fun differentOperationNeverDispatches() = test { f ->
        val r = Rig(); r.raw = output("{\"action\":\"system_button\",\"button\":\"Back\"}")
        val before = r.node
        val outcome = r.run(f)
        assertEquals(DeviceOutcomeStatus.NEEDS_USER, outcome.status)
        r.counts(1, 1, 0)
        assertEquals("Wrong action must retain native state", before, r.node)
        assertArrayEquals(ByteArray(3), r.bytes)
    }
    @Test fun modelErrorWipesCapturedBytes() = test { f ->
        val r = Rig(); r.afterModel = { error("injected model failure") }
        try { r.run(f); fail("Expected failure") } catch (_: IllegalStateException) { }
        r.counts(1, 1, 0); assertArrayEquals(ByteArray(3), r.bytes)
    }
    @Test fun sourceBitmapRecycledWhenGeometryValidationThrows() = test { f ->
        val bitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val port = AndroidOwlCapturePort(f.context) {
            com.unoone.agent.core.model.Result.Success(com.unoone.agent.phonecontrol.CapturedScreen(bitmap, 0, 0, bounds, 0, 1))
        }
        try { port.capture(); fail("Mismatched geometry must fail") } catch (_: IllegalStateException) { }
        assertTrue(bitmap.isRecycled)
    }
    @Test fun nativeOpenAndReadDoNotClaimModelCalls() = test { f ->
        val open = Rig(); assertEquals(DeviceOutcomeStatus.VERIFIED, open.run(f, NativeDeviceGoal.OpenApp(pkg)).status); open.counts(0, 0, 1)
        val read = Rig(); assertEquals(DeviceOutcomeStatus.VERIFIED, read.run(f, NativeDeviceGoal.ReadScreen(pkg)).status); read.counts(0, 0, 0)
    }
}
