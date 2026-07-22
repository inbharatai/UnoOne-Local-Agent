package com.unoone.agent.modelmanager

import kotlinx.serialization.Serializable

@Serializable
data class E4bQualificationRecord(
    val modelSha256: String,
    val modelSizeBytes: Long,
    val loaded: Boolean,
    val strictSelfTestPassed: Boolean,
    val backend: String,
    val evaluationPassed: Boolean,
    val sustainedRunCompleted: Boolean,
    val noCrashAnrOom: Boolean,
    val deviceBuild: String,
    val qualifiedAtMs: Long
)

object E4bCleanupGate {
    const val SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    const val SIZE_BYTES = 3_659_530_240L

    fun rejectionReason(record: E4bQualificationRecord?, userApproved: Boolean): String? = when {
        !userApproved -> "User approval is required"
        record == null -> "No E4B device qualification record exists"
        record.modelSha256 != SHA256 || record.modelSizeBytes != SIZE_BYTES -> "Qualified artifact identity does not match E4B"
        !record.loaded -> "E4B load gate has not passed"
        !record.strictSelfTestPassed -> "Strict E4B self-test has not passed"
        record.backend.isBlank() -> "Qualified backend is missing"
        !record.evaluationPassed -> "Required E4B evaluation suite has not passed"
        !record.sustainedRunCompleted -> "Sustained device run has not completed"
        !record.noCrashAnrOom -> "Crash/ANR/OOM stability gate has not passed"
        record.deviceBuild.isBlank() -> "Qualified device/build identity is missing"
        else -> null
    }
}
