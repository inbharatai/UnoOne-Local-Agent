package com.unoone.agent.skills

import com.unoone.agent.core.device.*
import com.unoone.agent.core.integrations.*
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class SkillsV2Test {
    private val condition = SkillCondition(ConditionKind.FOREGROUND_PACKAGE, "com.example.app")
    private fun skill(version: Int = 1) = SkillsV2("open-app", version, "Observe app", setOf("observe"), SkillRisk.READ_ONLY,
        mapOf("com.example.app" to 1L), listOf(SkillStep(DeviceAction.Observe, preconditions = listOf(condition), postconditions = listOf(condition))))
    private fun state(pkg: String = "com.example.app", id: String = "fresh", time: Long = 100): PerceptionState {
        val bounds = RectData(0, 0, 100, 100)
        return PerceptionState(UiSnapshot(id, time, 1, bounds, listOf(UiWindow(1, pkg, bounds, emptyList()))))
    }
    private fun fixtures() = listOf(SkillFixture("foreground", listOf(SkillReplayFrame(state(), state(id = "after", time = 101))), DeviceAuthorization(observe = true), setOf(skill().digest(), skill(2).digest()), skill().appVersions, "offline-test"))
    @Test fun typedSerializationRoundTrip() {
        val original = skill()
        assertEquals(original, DeviceActionCodec.json.decodeFromString<SkillsV2>(DeviceActionCodec.json.encodeToString(original)))
    }
    @Test fun exactReviewRequiredAndOldVersionPreserved() {
        val dir = Files.createTempDirectory("skills-v2").toFile()
        try {
            val store = SkillsV2Store(dir); val old = skill(); val next = skill(2)
            store.saveInitial(old, true)
            assertTrue(runCatching { store.approveCandidate(old, next, fixtures(), next.digest(), false) }.isFailure)
            assertTrue(runCatching { store.approveCandidate(old, next, fixtures(), "wrong", true) }.isFailure)
            store.approveCandidate(old, next, fixtures(), next.digest(), true)
            assertEquals(old, store.read(old.id, 1)); assertEquals(next, store.read(old.id, 2))
            assertTrue(runCatching { store.approveCandidate(old, next, fixtures(), next.digest(), true) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun regressionsAndPermissionRiskActionChangesAreVisible() {
        val next = skill(2).copy(requiredPermissions = setOf("control"), risk = SkillRisk.DEVICE_CONTROL,
            steps = listOf(SkillStep(DeviceAction.Home, preconditions = listOf(condition),
                postconditions = listOf(condition.copy(packageName = "other.app")))))
        val review = SkillCandidateReview.compare(skill(), next, fixtures())
        assertTrue(review.permissionsChanged && review.riskChanged && review.actionsChanged)
        assertEquals(setOf("foreground"), review.regressions)
    }
    @Test fun staleCoordinatesAndMissingSelectorsRejected() {
        assertTrue(runCatching { SkillStep(DeviceAction.ClickNode("old", "node"), preconditions = listOf(condition), postconditions = listOf(condition)) }.isFailure)
        assertTrue(runCatching { SkillStep(DeviceAction.Swipe("old", 1, 1, 2, 2), preconditions = listOf(condition), postconditions = listOf(condition)) }.isFailure)
        assertTrue(runCatching { SkillSelector("com.example.app", "missing").resolve(state()) }.isFailure)
    }
    @Test fun selectorRebindsFreshNodeAndRejectsAmbiguity() {
        val bounds = RectData(0, 0, 100, 100)
        val node = UiNode("new-node", 1, "0", "com.example.app", "Button", resourceId = "app:id/button", bounds = bounds, clickable = true)
        fun withNodes(nodes: List<UiNode>) = PerceptionState(UiSnapshot("new-snapshot", 100, 1, bounds, listOf(UiWindow(1, "com.example.app", bounds, nodes))))
        val selector = SkillSelector("com.example.app", "app:id/button")
        val step = SkillStep(DeviceAction.ClickNode("old", "old"), selector, listOf(condition), listOf(condition))
        assertEquals(DeviceAction.ClickNode("new-snapshot", "new-node"), step.bind(withNodes(listOf(node))))
        assertTrue(runCatching { selector.resolve(withNodes(listOf(node, node.copy(id = "duplicate", path = "1")))) }.isFailure)
    }
    @Test fun nativeStatsSeparateVersionsAndRetainWorkflow() {
        val dir = Files.createTempDirectory("skills-stats").toFile()
        try {
            val store = SkillsV2Store(dir); val original = skill()
            store.saveInitial(original, true)
            store.recordNativeRun(original, true, original.appVersions, "test-device-build")
            store.recordNativeRun(original, false, original.appVersions, "test-device-build")
            assertEquals(1L, store.stats(original).verified); assertEquals(1L, store.stats(original).failed)
            assertEquals(original, store.read(original.id, 1))
        } finally { dir.deleteRecursively() }
    }
    @Test fun noEmptyFixtureApproval() { assertTrue(runCatching { SkillCandidateReview.compare(skill(), skill(2), emptyList()) }.isFailure) }
    @Test fun integrationsAdvertiseUnavailableHonestly() {
        assertFalse(OptionalAppFunctions().availability.available)
        assertTrue(OptionalAppFunctions().discover().isEmpty())
        val brain = ExperimentalQwenBrain()
        assertFalse(brain.capabilities.devicePlanning); assertFalse(brain.capabilities.vision); assertFalse(brain.capabilities.chat)
        assertFalse(brain.descriptor.availability.available)
    }
    @Test fun bridgeRejectsUnknownVersionAndDuplicateCapability() {
        assertTrue(runCatching { BridgeDiscovery(999, emptyList()) }.isFailure)
        assertTrue(runCatching { BridgeDiscovery(1, listOf(BridgeCapability("a", true), BridgeCapability("a", false))) }.isFailure)
    }
    @Test fun offlineFixtureRequiresReviewedAuthorizationAndOrderedFrames() {
        val fixture = fixtures().single()
        assertTrue(SkillCandidateReview.replay(skill(), fixture))
        assertFalse(SkillCandidateReview.replay(skill(), fixture.copy(authorization = DeviceAuthorization())))
        assertFalse(SkillCandidateReview.replay(skill(), fixture.copy(reviewedSkillDigests = emptySet())))
        assertFalse(SkillCandidateReview.replay(skill(), fixture.copy(frames = listOf(SkillReplayFrame(state(), state())))))
        assertFalse(SkillCandidateReview.replay(skill(), fixture.copy(frames = listOf(SkillReplayFrame(state(), state(id = "older", time = 99))))))
    }
    @Test fun finalObservationCancellationNeverSucceeds() = kotlinx.coroutines.runBlocking {
        val epochs = DeviceEpoch()
        val guard = DeviceExecutionGuard(epochs, epochs.current(), { true }, DeviceAuthorization(observe = true))
        val adapter = object : DeviceAdapter {
            var observations = 0
            override suspend fun observe(): PerceptionState {
                if (++observations == 2) epochs.cancel()
                return state(id = "frame$observations", time = 100L + observations)
            }
            override suspend fun execute(action: DeviceAction, state: PerceptionState, guard: DeviceExecutionGuard) = DeviceDispatch(true)
            override suspend fun awaitSettled(afterEventSequence: Long, timeoutMs: Long) = Unit
        }
        try { SkillsV2Runner(adapter) { 103 }.run(skill(), guard, skill().appVersions); fail("Expected cancellation") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
