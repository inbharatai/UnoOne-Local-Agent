package com.unoone.agent.skills

import com.unoone.agent.core.device.*
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Structured native workflows; legacy prompt skills are not implicitly migrated or executed. */
@Serializable data class SkillSelector(val packageName: String, val resourceId: String) {
    init { require(packageName.isNotBlank() && resourceId.isNotBlank()) }
    fun resolve(state: PerceptionState): UiNode = state.snapshot.nodes.filter {
        it.packageName == packageName && it.resourceId == resourceId && it.enabled && !it.password
    }.singleOrNull() ?: error("Selector absent or ambiguous")
}
@Serializable enum class ConditionKind { FOREGROUND_PACKAGE, NODE_EXISTS, FIELD_EQUALS }
@Serializable data class SkillCondition(val kind: ConditionKind, val packageName: String,
    val resourceId: String = "", val expected: String = "") {
    fun satisfied(state: PerceptionState): Boolean = when (kind) {
        ConditionKind.FOREGROUND_PACKAGE -> state.snapshot.windows.firstOrNull()?.packageName == packageName
        ConditionKind.NODE_EXISTS -> runCatching { SkillSelector(packageName, resourceId).resolve(state) }.isSuccess
        ConditionKind.FIELD_EQUALS -> runCatching { SkillSelector(packageName, resourceId).resolve(state).let { it.editable && it.text == expected } }.getOrDefault(false)
    }
}
@Serializable enum class SkillRisk { READ_ONLY, DEVICE_CONTROL, SENSITIVE }
@Serializable data class SkillStep(val action: DeviceAction, val selector: SkillSelector? = null,
    val preconditions: List<SkillCondition>, val postconditions: List<SkillCondition>) {
    init {
        require(preconditions.isNotEmpty() && postconditions.isNotEmpty())
        require(action !is DeviceAction.Swipe && action !is DeviceAction.ClickVisualTarget) { "Persistent coordinates are not replayable" }
        require(action.nodeRef() == null || selector != null) { "Node actions require stable selector" }
        require(action !is DeviceAction.Done && action !is DeviceAction.Fail && action !is DeviceAction.AskUser &&
            action !is DeviceAction.Escalate && action !is DeviceAction.RequestConfirmation) { "Workflow steps must be native operations, not planner control flow" }
    }
    fun bind(state: PerceptionState): DeviceAction {
        val id = selector?.resolve(state)?.id
        val s = state.snapshot.id
        return when (val a = action) {
            is DeviceAction.ClickNode -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.LongPressNode -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.SetText -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.ClearText -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.FocusNode -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.Scroll -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            is DeviceAction.ReadNode -> a.copy(snapshotId = s, nodeId = requireNotNull(id))
            else -> a
        }
    }
}
@Serializable data class SkillsV2(val id: String, val version: Int, val title: String,
    val requiredPermissions: Set<String>, val risk: SkillRisk,
    val appVersions: Map<String, Long>, val steps: List<SkillStep>, val schemaVersion: Int = 2) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && version > 0 && schemaVersion == 2)
        require(title.length in 1..160 && steps.size in 1..32 && appVersions.isNotEmpty())
        require(appVersions.values.all { it >= 0 })
    }
    fun digest() = UiStateHasher.digest(DeviceActionCodec.json.encodeToString(this))
}
@Serializable data class SkillRunRecord(val skillId: String, val version: Int, val verified: Boolean,
    val installedVersions: Map<String, Long>, val deviceBuild: String)
@Serializable data class SkillVersionStats(val skillId: String, val version: Int,
    val verified: Long = 0, val failed: Long = 0, val appVersions: Map<String, Long> = emptyMap())

/** Native execution only. Every dispatch still requires the existing safety/epoch guard. */
class SkillsV2Runner(private val adapter: DeviceAdapter, private val clockMs: () -> Long) {
    private val mutex = Mutex()
    suspend fun run(skill: SkillsV2, guard: DeviceExecutionGuard, installedVersions: Map<String, Long>): Boolean =
        withTimeout(60_000) { mutex.withLock { runLocked(skill, guard, installedVersions) } }
    private suspend fun runLocked(skill: SkillsV2, guard: DeviceExecutionGuard, installedVersions: Map<String, Long>): Boolean {
        require(skill.appVersions.all { installedVersions[it.key] == it.value }) { "App version changed: re-review required" }
        require(guard.authorization.observe) { "Observation consent required" }
        suspend fun check() { currentCoroutineContext().ensureActive(); guard.check() }
        var previous: UiSnapshot? = null
        for (step in skill.steps) {
            check()
            val before = adapter.observe()
            check()
            DeviceActionValidator.validate(DeviceAction.Observe, before, clockMs())
            previous?.let { require(before.snapshot.capturedAtMs >= it.capturedAtMs && before.snapshot.eventSequence >= it.eventSequence) }
            require(step.preconditions.all { it.satisfied(before) }) { "Precondition failed" }
            val action = step.bind(before)
            guard.validate(action, before, clockMs())
            if (!adapter.execute(action, before, guard).accepted) return false
            adapter.awaitSettled(before.snapshot.eventSequence, 1500)
            check()
            val after = adapter.observe()
            check()
            require(after.snapshot.id != before.snapshot.id && after.snapshot.capturedAtMs >= before.snapshot.capturedAtMs && after.snapshot.eventSequence >= before.snapshot.eventSequence)
            DeviceActionValidator.validate(DeviceAction.Observe, after, clockMs())
            if (!step.postconditions.all { it.satisfied(after) }) return false
            check()
            previous = after.snapshot
        }
        check()
        return true
    }
}

data class SkillReplayFrame(val before: PerceptionState, val after: PerceptionState)
data class SkillFixture(val id: String, val frames: List<SkillReplayFrame>,
    val authorization: DeviceAuthorization = DeviceAuthorization(),
    val reviewedSkillDigests: Set<String> = emptySet(), val appVersions: Map<String, Long> = emptyMap(),
    val deviceBuild: String = "", val schemaVersion: Int = 2,
    val reviewedActionDigests: Set<String> = emptySet())
data class SkillReview(val oldDigest: String, val candidateDigest: String,
    val permissionsChanged: Boolean, val riskChanged: Boolean, val selectorsChanged: Boolean,
    val actionsChanged: Boolean, val versionsChanged: Boolean, val regressions: Set<String>,
    val candidateFailures: Set<String>, val fixtureCount: Int)
object SkillCandidateReview {
    /** Offline fixture validation, NOT evidence that a real device side effect succeeded. */
    fun replay(skill: SkillsV2, fixture: SkillFixture): Boolean = runCatching {
        require(fixture.frames.size == skill.steps.size)
        require(skill.digest() in fixture.reviewedSkillDigests && fixture.appVersions == skill.appVersions)
        require(fixture.deviceBuild.isNotBlank() && fixture.schemaVersion == skill.schemaVersion)
        fixture.frames.zipWithNext().forEach { (a, b) ->
            require(a.after.snapshot.id == b.before.snapshot.id && UiStateHasher.hash(a.after.snapshot) == UiStateHasher.hash(b.before.snapshot))
            require(a.after.snapshot.capturedAtMs == b.before.snapshot.capturedAtMs && a.after.snapshot.eventSequence == b.before.snapshot.eventSequence)
        }
        skill.steps.zip(fixture.frames).all { (step, frame) ->
            val before = frame.before.snapshot; val after = frame.after.snapshot
            require(after.id != before.id && after.capturedAtMs >= before.capturedAtMs && after.eventSequence >= before.eventSequence)
            val action = step.bind(frame.before)
            DeviceActionValidator.validate(action, frame.before, before.capturedAtMs)
            when (DeviceSafetyPolicy.decide(action, frame.before, fixture.authorization)) {
                SafetyDecision.Allow -> Unit
                is SafetyDecision.Confirm -> require(DeviceActionCodec.digest(action) in fixture.reviewedActionDigests)
                is SafetyDecision.Handover -> error("Native policy rejected offline fixture")
            }
            if (action.nodeRef() != null && action !is DeviceAction.ReadNode) {
                require(step.postconditions.any { it.kind != ConditionKind.FOREGROUND_PACKAGE && !it.satisfied(frame.before) && it.satisfied(frame.after) })
            }
            step.preconditions.all { it.satisfied(frame.before) } && step.postconditions.all { it.satisfied(frame.after) }
        }
    }.getOrDefault(false)
    fun compare(old: SkillsV2, candidate: SkillsV2, fixtures: List<SkillFixture>): SkillReview {
        require(old.id == candidate.id && candidate.version > old.version)
        require(fixtures.isNotEmpty() && fixtures.map { it.id }.distinct().size == fixtures.size)
        val failures = fixtures.filterNot { replay(candidate, it) }.map { it.id }.toSet()
        return SkillReview(old.digest(), candidate.digest(), old.requiredPermissions != candidate.requiredPermissions,
            old.risk != candidate.risk, old.steps.map { it.selector } != candidate.steps.map { it.selector },
            old.steps.map { it.action } != candidate.steps.map { it.action }, old.appVersions != candidate.appVersions,
            fixtures.filter { replay(old, it) && it.id in failures }.map { it.id }.toSet(), failures, fixtures.size)
    }
}

/** Append-only versions. Only explicit native review approval can add a candidate. No active pointer is switched. */
class SkillsV2Store(private val directory: File) {
    private fun file(id: String, version: Int): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && version > 0)
        return File(directory, "$id-v$version.json")
    }
    fun read(id: String, version: Int): SkillsV2 = DeviceActionCodec.json.decodeFromString(file(id, version).readText())
    @Synchronized fun saveInitial(skill: SkillsV2, explicitlyApproved: Boolean) {
        require(explicitlyApproved && skill.version == 1)
        append(skill)
    }
    @Synchronized fun approveCandidate(old: SkillsV2, candidate: SkillsV2, fixtures: List<SkillFixture>,
        reviewedCandidateDigest: String, explicitlyApproved: Boolean) {
        require(explicitlyApproved && reviewedCandidateDigest == candidate.digest()) { "Explicit exact-version review required" }
        require(read(old.id, old.version) == old) { "Review baseline changed" }
        val review = SkillCandidateReview.compare(old, candidate, fixtures)
        require(review.candidateFailures.isEmpty() && review.regressions.isEmpty()) { "Fixture replay failed" }
        append(candidate)
    }
    /** Native outcome counters are separate from immutable workflow definitions; no model success claims. */
    @Synchronized fun recordNativeRun(skill: SkillsV2, verified: Boolean, installedVersions: Map<String, Long>, deviceBuild: String) {
        require(read(skill.id, skill.version) == skill && deviceBuild.length in 1..256)
        val record = SkillRunRecord(skill.id, skill.version, verified, installedVersions, deviceBuild)
        val target = File(directory, "${skill.id}-v${skill.version}-run-${java.util.UUID.randomUUID()}.json")
        require(target.createNewFile())
        target.writeText(DeviceActionCodec.json.encodeToString(record))
    }
    fun stats(skill: SkillsV2): SkillVersionStats {
        val prefix = "${skill.id}-v${skill.version}-run-"
        val records = directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
            .map { DeviceActionCodec.json.decodeFromString<SkillRunRecord>(it.readText()) }
        return SkillVersionStats(skill.id, skill.version, records.count { it.verified }.toLong(),
            records.count { !it.verified }.toLong(), skill.appVersions)
    }
    private fun append(skill: SkillsV2) {
        directory.mkdirs()
        val target = file(skill.id, skill.version)
        require(target.createNewFile()) { "Never overwrite an existing workflow version" }
        // A crash leaves an invalid new version, not a modified old workflow; callers fail closed on decode.
        target.writeText(DeviceActionCodec.json.encodeToString(skill))
    }
}
