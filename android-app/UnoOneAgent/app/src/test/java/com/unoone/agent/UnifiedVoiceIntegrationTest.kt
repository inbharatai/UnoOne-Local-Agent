package com.unoone.agent

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import com.unoone.agent.core.device.*
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.*
import com.unoone.agent.core.task.*
import com.unoone.agent.core.voice.*
import com.unoone.agent.storage.db.UnoOneDatabase
import com.unoone.agent.task.NativeTaskFixture
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real intake -> runtime admission -> native session; dependencies never manufacture authority. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UnifiedVoiceIntegrationTest {
    private lateinit var fixture: NativeTaskFixture
    private lateinit var db: UnoOneDatabase
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var intake: UnifiedVoiceCoordinator
    private val gmail = "com.google.android.gm"
    private val adapter = RecordingAdapter()

    private class RecordingAdapter : DeviceAdapter {
        var pkg = "com.unoone.agent"
        var sequence = 0L
        var reject = false
        val actions = java.util.concurrent.CopyOnWriteArrayList<DeviceAction>()
        override fun withObservationPackages(packages: Set<String>): DeviceAdapter = this
        override suspend fun observe(): PerceptionState {
            val bounds = RectData(0, 0, 100, 100)
            return PerceptionState(UiSnapshot("s${sequence++}", SystemClock.elapsedRealtime(), actions.size.toLong(), bounds,
                listOf(UiWindow(1, pkg, bounds, emptyList()))))
        }
        override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard): DeviceDispatch {
            guard.validate(action, state, SystemClock.elapsedRealtime())
            actions += action
            if (!reject && action is DeviceAction.OpenApp) pkg = action.packageName
            return DeviceDispatch(!reject)
        }
        override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) {}
    }

    @Before fun setup() = runBlocking {
        // Main actions must not be queued behind the test's runBlocking on the Robolectric thread.
        Dispatchers.setMain(Dispatchers.Unconfined)
        AgentRuntimeGate.setEnabled(true)
        fixture = NativeTaskFixture()
        db = Room.inMemoryDatabaseBuilder(fixture.context, UnoOneDatabase::class.java).allowMainThreadQueries().build()
        orchestrator = AgentOrchestrator(fixture.context, db.noteDao(), db.actionLogDao(), db.memoryDao(), db.skillDao(),
            deviceBrainProvider = { error("Native route called model controller") },
            deviceBrainFactory = { error("Native route constructed model controller") },
            deviceAdapterProvider = { adapter })
        val voice = mock<VoiceModule>()
        whenever(voice.currentLanguage()).thenReturn("en")
        // Explicit language and trace avoid executing currentLanguage() with a pending matcher.
        whenever(voice.speakAwait(any(), any(), anyOrNull())).thenReturn(Result.Success(Unit))
        orchestrator.setVoiceModule(voice)
        orchestrator.ensureTaskScopesReady()
        intake = UnifiedVoiceCoordinator(fixture.context, orchestrator, VoiceAppResolver {
            AppResolution.Resolved(AppChoice(gmail, "Gmail"))
        })
    }
    @After fun cleanup() {
        intake.clear()
        orchestrator.taskRuntime.coordinator.close()
        fixture.close()
        db.close()
        Dispatchers.resetMain()
    }
    private fun ingress(text: String) = VoiceIngress(java.util.UUID.randomUUID().toString(), text,
        GlobalTaskCancellation.generation, SystemClock.elapsedRealtime(), null, null)

    @Test fun staleCaptureCannotDispatchOrPersist() = runBlocking {
        val stale = ingress("open Gmail").copy(captureGlobalGeneration = GlobalTaskCancellation.generation - 1)
        intake.accept(stale)
        intake.accept(stale.copy(requestId = java.util.UUID.randomUUID().toString(), transcript = "create note stale"))
        assertTrue(adapter.actions.isEmpty())
        assertTrue(orchestrator.taskRuntime.tasks.value.isEmpty())
        assertTrue(db.noteDao().recent(100).isEmpty())
        assertNull(intake.review.value)
    }

    @Test fun nativeOpenCompletesWhileModelLeaseIsUnavailable() = runBlocking {
        ProcessTaskResources.model.withLease(TaskId("model-held-by-other-task"), {}) {
            withTimeout(10_000) { intake.accept(ingress("open Gmail")) }
            assertEquals(listOf(DeviceAction.OpenApp(gmail)), adapter.actions.toList())
            assertEquals(TaskOutcome.VERIFIED, orchestrator.taskRuntime.results.value.values.single().result.outcome)
            assertEquals(TaskId("model-held-by-other-task"), ProcessTaskResources.model.owner())
        }
    }

    @Test fun nativeGreetingRespondsWithoutModelLeaseOrAction() = runBlocking {
        ProcessTaskResources.model.withLease(TaskId("model-held-by-other-task"), {}) {
            withTimeout(10_000) { intake.accept(ingress("hello")) }
            assertTrue(adapter.actions.isEmpty())
            assertEquals(TaskOutcome.RESPONDED, orchestrator.taskRuntime.results.value.values.single().result.outcome)
            assertEquals(TaskId("model-held-by-other-task"), ProcessTaskResources.model.owner())
        }
    }

    @Test fun directNotePersistsWhileModelLeaseIsUnavailable() = runBlocking {
        ProcessTaskResources.model.withLease(TaskId("model-held-by-other-task"), {}) {
            withTimeout(10_000) { intake.accept(ingress("create note voice integration")) }
            assertEquals(1, db.noteDao().recent(100).size)
            assertTrue(adapter.actions.isEmpty())
            assertEquals(TaskId("model-held-by-other-task"), ProcessTaskResources.model.owner())
        }
    }

    @Test fun homeWithoutAccessibilityRetainsUserControlAndNeverClaimsVerified() = runBlocking {
        withTimeout(10_000) { intake.accept(ingress("go home")) }
        assertTrue(orchestrator.isDeterministicVoiceRule("go home"))
        assertEquals(TaskOutcome.NEEDS_USER, orchestrator.taskRuntime.results.value.values.single().result.outcome)
        assertNull(intake.review.value)
        assertTrue(adapter.actions.isEmpty())
        assertFalse(intake.status.value.equals("Done", true))
        assertFalse(orchestrator.taskRuntime.results.value.values.any { it.result.outcome == TaskOutcome.VERIFIED })
    }

    @Test fun globalNavigationRetainsExactNativeGrantsWithoutModelOrOwl() = runBlocking {
        ProcessTaskResources.model.withLease(TaskId("model-held-by-other-task"), {}) {
            listOf("go home", "open recents", "open notifications").forEach { text ->
                val call = com.unoone.agent.localbrain.RuleBasedParser.parse(text)!!
                assertTrue(orchestrator.isDeterministicVoiceRule(text))
                val scope = orchestrator.authorizeTaskScope(text)
                assertTrue(scope.objectHandles.contains(com.unoone.agent.task.TaskToolAuthorization.handle(call)))
                assertTrue(scope.packages.isEmpty())
                assertFalse(scope.capabilities.contains(TaskCapability.MODEL))
                withTimeout(10_000) { intake.accept(ingress(text)) }
                assertNull(intake.review.value)
            }
            assertEquals(3, orchestrator.taskRuntime.results.value.size)
            assertTrue(orchestrator.taskRuntime.results.value.values.all { it.result.outcome == TaskOutcome.NEEDS_USER })
            assertTrue(adapter.actions.isEmpty())
            assertEquals(TaskId("model-held-by-other-task"), ProcessTaskResources.model.owner())
        }
        // Robolectric has no live accessibility service: this proves admission + fail-closed access,
        // not actual Home/Recents/Notifications dispatch or a hardware foreground predicate.
    }

    @Test fun busyUiWorkerQueuesFrozenRequestInsteadOfDroppingIt() = runBlocking {
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            ProcessTaskResources.ui.withLease(TaskId("busy-ui"), {}) { held.complete(Unit); release.await() }
        }
        held.await()
        val request = ingress("open Gmail")
        val queued = async { intake.accept(request) }
        try {
            withTimeout(10_000) {
                while (orchestrator.taskRuntime.tasks.value.isEmpty()) yield()
            }
            assertTrue(adapter.actions.isEmpty())
            assertFalse(queued.isCompleted)
        } finally { release.complete(Unit) }
        withTimeout(10_000) { holder.join(); queued.await() }
        assertEquals(listOf(DeviceAction.OpenApp(gmail)), adapter.actions.toList())
    }

    @Test fun nativeFailureDoesNotReplayThroughOwl() = runBlocking {
        adapter.reject = true
        withTimeout(10_000) { intake.accept(ingress("open Gmail")) }
        assertNull(intake.review.value)
        assertNotEquals(TaskOutcome.VERIFIED, orchestrator.taskRuntime.results.value.values.single().result.outcome)
        assertFalse(intake.status.value.equals("Done", true))
    }

    @Test fun missingCapturePermissionDoesNotRetainReplayableReview() = runBlocking {
        withTimeout(10_000) { intake.accept(ingress("use owl to open Gmail")) }
        assertNull(intake.review.value)
        intake.accept(ingress("confirm"))
        assertTrue(adapter.actions.isEmpty())
        assertTrue(orchestrator.taskRuntime.tasks.value.isEmpty())
    }
}
