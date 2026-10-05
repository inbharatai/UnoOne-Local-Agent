package com.unoone.agent.ui.viewmodel

import com.unoone.agent.core.device.*
import com.unoone.agent.skills.*
import java.io.InputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** File data never supplies authorization, fixtures, or a native semantic resolver. */
object SkillsV2Policy {
    const val MAX_BYTES = 128 * 1024
    fun readBounded(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= MAX_BYTES) { "Workflow exceeds 128 KiB" }
            out.write(buffer, 0, n)
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    }
    fun parse(raw: String): SkillsV2 {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return DeviceActionCodec.json.decodeFromString<SkillsV2>(raw).also { skill ->
            require(skill.requiredPermissions.all { it == "accessibility" }) { "Unsupported permission; manual review required" }
            require(skill.steps.all { step ->
                val selector = step.selector
                val action = step.action
                (selector == null || selector.packageName in skill.appVersions) &&
                    (step.preconditions + step.postconditions).all { it.packageName in skill.appVersions } &&
                    (action !is DeviceAction.OpenApp || action.packageName in skill.appVersions)
            }) { "Every action, selector and condition must be in the declared app scope" }
        }
    }
    fun canRun(skill: SkillsV2, digest: String, approvedSteps: Set<Int>, enabled: Boolean,
               accessibility: Boolean, installed: Map<String, Long>): Boolean =
        enabled && accessibility && skill.risk != SkillRisk.SENSITIVE && digest == skill.digest() &&
            approvedSteps == skill.steps.indices.toSet() && skill.appVersions.all { installed[it.key] == it.value } &&
            skill.steps.none { it.action is DeviceAction.OpenUri }

    fun export(skill: SkillsV2): String = DeviceActionCodec.json.encodeToString(skill)
    fun diff(old: SkillsV2?, skill: SkillsV2): String = if (old == null) "Initial version: explicit review required" else
        "Baseline v${old.version}; permissions changed: ${old.requiredPermissions != skill.requiredPermissions}; risk changed: ${old.risk != skill.risk}; apps changed: ${old.appVersions != skill.appVersions}; selectors changed: ${old.steps.map { it.selector } != skill.steps.map { it.selector }}; actions changed: ${old.steps.map { it.action } != skill.steps.map { it.action }}. Candidate approval unavailable until trusted native replay fixtures exist. Baseline is never overwritten."
}
