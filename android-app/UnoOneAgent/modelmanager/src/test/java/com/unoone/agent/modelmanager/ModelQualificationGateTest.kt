package com.unoone.agent.modelmanager

import org.junit.Assert.*
import org.junit.Test

class ModelQualificationGateTest {
    private val identity = ModelQualificationIdentity(
        "gemma-4-e2b", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        2588147712L, "Xiaomi 14", "device-build", "app-build", "runtime-version", "CPU"
    )
    private val complete = ModelQualificationRecord(
        identity, true, true, 50, true, true, true, true, true, true, 1L
    )

    @Test fun `every evidence gate and explicit approval are required`() {
        assertNull(ModelQualificationGate.rejectionReason(complete, identity, true))
        assertNotNull(ModelQualificationGate.rejectionReason(complete, identity, false))
        assertNotNull(ModelQualificationGate.rejectionReason(null, identity, true))
        listOf(
            complete.copy(loaded = false), complete.copy(strictSelfTestPassed = false),
            complete.copy(completedTaskCount = 49), complete.copy(taskSuitePassed = false),
            complete.copy(imageInputPassed = false), complete.copy(groundingPassed = false),
            complete.copy(sustainedRunCompleted = false), complete.copy(thermalPassed = false),
            complete.copy(noCrashAnrOom = false), complete.copy(qualifiedAtMs = 0)
        ).forEach { assertNotNull(ModelQualificationGate.rejectionReason(it, identity, true)) }
    }

    @Test fun `stale evidence cannot qualify a changed execution identity`() {
        listOf(
            identity.copy(modelId = "gemma-4-e4b"), identity.copy(artifactRevision = "a".repeat(40)),
            identity.copy(artifactSha256 = "a".repeat(64)), identity.copy(artifactSizeBytes = 1),
            identity.copy(deviceModel = "other"), identity.copy(deviceBuild = "other"),
            identity.copy(appBuild = "other"), identity.copy(runtimeVersion = "other"),
            identity.copy(backend = "GPU")
        ).forEach { assertNotNull(ModelQualificationGate.rejectionReason(complete, it, true)) }
    }

    @Test fun `malformed identities fail even when record and expected match`() {
        listOf(identity.copy(appBuild = ""), identity.copy(artifactRevision = "main"),
            identity.copy(artifactSha256 = ""), identity.copy(artifactSizeBytes = 0))
            .forEach { assertNotNull(ModelQualificationGate.rejectionReason(complete.copy(identity = it), it, true)) }
    }
}
