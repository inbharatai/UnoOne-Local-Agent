package com.unoone.agent

import com.unoone.agent.core.device.DeviceAction
import com.unoone.agent.skills.*
import com.unoone.agent.ui.viewmodel.SkillsV2Policy
import org.junit.Assert.*
import org.junit.Test

class SkillsV2PolicyTest {
    private val condition = SkillCondition(ConditionKind.FOREGROUND_PACKAGE, "com.example.app")
    private fun skill() = SkillsV2("sample", 1, "Read", setOf("accessibility"), SkillRisk.READ_ONLY,
        mapOf("com.example.app" to 1L), listOf(SkillStep(DeviceAction.Observe,
            preconditions = listOf(condition), postconditions = listOf(condition))))
    @Test fun strictImportRoundTripAndNoJsonAuthority() {
        val raw = SkillsV2Policy.export(skill())
        assertEquals(skill(), SkillsV2Policy.parse(raw))
        assertTrue(runCatching { SkillsV2Policy.parse(raw.dropLast(1) + ",\"safeBypass\":true}") }.isFailure)
        assertTrue(runCatching { SkillsV2Policy.parse(raw.replace("Observe", "Shell")) }.isFailure)
    }
    @Test fun boundedReadRejectsExcessAndInvalidUtf8() {
        assertTrue(runCatching { SkillsV2Policy.readBounded(ByteArray(SkillsV2Policy.MAX_BYTES + 1).inputStream()) }.isFailure)
        assertTrue(runCatching { SkillsV2Policy.readBounded(byteArrayOf(0xC3.toByte(), 0x28).inputStream()) }.isFailure)
    }
    @Test fun exactReviewScopeAndRuntimePermissionsRequired() {
        val s = skill()
        fun allowed(digest: String = s.digest(), steps: Set<Int> = setOf(0), enabled: Boolean = true,
                    accessibility: Boolean = true, versions: Map<String, Long> = s.appVersions) =
            SkillsV2Policy.canRun(s, digest, steps, enabled, accessibility, versions)
        assertTrue(allowed())
        assertFalse(allowed(digest = "different"))
        assertFalse(allowed(steps = emptySet()))
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(accessibility = false))
        assertFalse(allowed(versions = mapOf("com.example.app" to 2L)))
        assertFalse(SkillsV2Policy.canRun(s.copy(risk = SkillRisk.SENSITIVE), s.digest(), setOf(0), true, true, s.appVersions))
    }
    @Test fun candidateDiffDoesNotPretendFixturePass() {
        assertTrue(SkillsV2Policy.diff(skill(), skill().copy(version = 2)).contains("unavailable"))
        assertTrue(runCatching { SkillsV2Policy.parse(SkillsV2Policy.export(skill().copy(requiredPermissions = setOf("root")))) }.isFailure)
    }
}
