package com.unoone.agent.modelmanager

import kotlinx.serialization.Serializable

/** Evidence is scoped to an exact artifact, device, application and runtime/backend combination. */
@Serializable
data class ModelQualificationIdentity(
    val modelId: String,
    val artifactRevision: String,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val deviceModel: String,
    val deviceBuild: String,
    val appBuild: String,
    val runtimeVersion: String,
    val backend: String
)

@Serializable
data class ModelQualificationRecord(
    val identity: ModelQualificationIdentity,
    val loaded: Boolean,
    val strictSelfTestPassed: Boolean,
    val completedTaskCount: Int,
    val taskSuitePassed: Boolean,
    val imageInputPassed: Boolean,
    val groundingPassed: Boolean,
    val sustainedRunCompleted: Boolean,
    val thermalPassed: Boolean,
    val noCrashAnrOom: Boolean,
    val qualifiedAtMs: Long
)

/** Pure validation only: this gate never deletes artifacts or grants automatic migration. */
object ModelQualificationGate {
    fun rejectionReason(
        record: ModelQualificationRecord?,
        expected: ModelQualificationIdentity,
        userApproved: Boolean
    ): String? = when {
        !userApproved -> "Explicit user approval is required"
        record == null -> "No model qualification record exists"
        record.identity != expected -> "Qualification identity does not match the current device/app/runtime/backend/artifact"
        expected.modelId.isBlank() || expected.deviceModel.isBlank() || expected.deviceBuild.isBlank() ||
            expected.appBuild.isBlank() || expected.runtimeVersion.isBlank() || expected.backend.isBlank() -> "Qualification identity is incomplete"
        !expected.artifactRevision.matches(Regex("[0-9a-f]{40}")) ||
            !expected.artifactSha256.matches(Regex("[0-9a-f]{64}")) ||
            expected.artifactSizeBytes <= 0 -> "Immutable artifact identity is invalid"
        record.qualifiedAtMs <= 0 -> "Qualification timestamp is missing"
        !record.loaded || !record.strictSelfTestPassed -> "Load and strict self-test gates must pass"
        record.completedTaskCount < 50 || !record.taskSuitePassed -> "The 50-task qualification suite must pass"
        !record.imageInputPassed -> "Image input qualification must pass"
        !record.groundingPassed -> "Grounding qualification must pass"
        !record.sustainedRunCompleted || !record.thermalPassed -> "Sustained thermal qualification must pass"
        !record.noCrashAnrOom -> "No-crash/ANR/OOM qualification must pass"
        else -> null
    }
}
