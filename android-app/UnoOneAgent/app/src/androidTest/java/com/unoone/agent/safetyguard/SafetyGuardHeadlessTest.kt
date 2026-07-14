package com.unoone.agent.safetyguard

import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.CanonicalToolRegistry
import com.unoone.agent.core.model.RiskLevel
import com.unoone.agent.safety.SafetyPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real on-device, HEADLESS agent safety-classification gate (DEVICE_VERIFICATION §3: "unknown-tool
 * rejection", "missing-argument rejection", "destructive-action confirmation", "blocked
 * payment/credential/OTP request"). Proves the safety verdict as pure logic on the device's ART
 * (the same logic the JVM SafetyGuardTest covers, but on-device — the rules forbid JVM-only
 * verification). Three layers:
 *
 *  1. [SafetyGuard.classify] / [classifyFromInput] — risk tier per tool name and per free-text input.
 *  2. [CanonicalToolRegistry] — unknown-tool rejection (`isKnown`) and missing-required-argument
 *     detection against the canonical schema. Note `make_payment` is BOTH non-canonical (brain rejects
 *     it as an unknown tool) AND `SafetyGuard` tier BLOCK — a dual-layer block.
 *  3. [SafetyPipeline] — the pipeline decision: isBlocked / requiresConfirmation.
 *
 * Run: am instrument -e class com.unoone.agent.safetyguard.SafetyGuardHeadlessTest ...
 */
class SafetyGuardHeadlessTest {

    private val guard = SafetyGuard()

    // --- 1. SafetyGuard tool-name classification ---
    @Test
    fun directToolsAreAutoAllowed() {
        assertEquals(RiskLevel.DIRECT, guard.classify("create_note"))
        assertEquals(RiskLevel.DIRECT, guard.classify("open_chrome"))
        assertEquals(RiskLevel.DIRECT, guard.classify("check_calendar"))
    }

    @Test
    fun confirmToolsRequireSingleConfirmation() {
        assertEquals(RiskLevel.CONFIRM, guard.classify("open_url"))
        assertEquals(RiskLevel.CONFIRM, guard.classify("read_screen"))
        assertEquals(RiskLevel.CONFIRM, guard.classify("share_text"))
    }

    @Test
    fun destructiveToolsRequireStrongConfirmation() {
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classify("delete_notes"))
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classify("delete_all_notes"))
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classify("describe_scene"))
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classify("system_control"))
    }

    @Test
    fun blockedToolsAreNeverExecuted() {
        assertEquals(RiskLevel.BLOCK, guard.classify("make_payment"))
        assertEquals(RiskLevel.BLOCK, guard.classify("send_message"))
        assertEquals(RiskLevel.BLOCK, guard.classify("access_passwords"))
        assertEquals(RiskLevel.BLOCK, guard.classify("install_app"))
        assertEquals(RiskLevel.BLOCK, guard.classify("silent_control"))
    }

    @Test
    fun unknownToolDefaultsToStrongConfirm() {
        // Unknown tools are never auto-allowed: they demand strong confirmation.
        assertEquals(RiskLevel.STRONG_CONFIRM, guard.classify("totally_bogus_xyz"))
    }

    // --- 1b. SafetyGuard input-text escalation ---
    @Test
    fun classifyFromInputBlocksPaymentsAndPasswordsAndOtp() {
        assertEquals("payment input must be BLOCK", RiskLevel.BLOCK, guard.classifyFromInput("please pay my credit card bill now"))
        assertEquals("password input must be BLOCK", RiskLevel.BLOCK, guard.classifyFromInput("read my saved passwords"))
        assertEquals("otp input must be BLOCK", RiskLevel.BLOCK, guard.classifyFromInput("enter the otp 123456"))
        assertEquals("bank input must be BLOCK", RiskLevel.BLOCK, guard.classifyFromInput("check my bank account balance"))
    }

    @Test
    fun classifyFromInputEscalatesDestructiveText() {
        assertEquals("delete-all input must be STRONG_CONFIRM", RiskLevel.STRONG_CONFIRM, guard.classifyFromInput("delete all my notes now"))
        assertEquals("plain input must stay DIRECT", RiskLevel.DIRECT, guard.classifyFromInput("open chrome"))
    }

    // --- 2. CanonicalToolRegistry: unknown-tool + missing-argument ---
    @Test
    fun registryRejectsUnknownAndBlockedToolNames() {
        assertTrue("create_note is canonical", CanonicalToolRegistry.isKnown("create_note"))
        assertTrue("delete_all_notes is canonical", CanonicalToolRegistry.isKnown("delete_all_notes"))
        // Blocked tools are NOT canonical — the brain rejects the tool name before SafetyGuard sees it.
        assertFalse("make_payment is not canonical", CanonicalToolRegistry.isKnown("make_payment"))
        assertFalse("send_message is not canonical", CanonicalToolRegistry.isKnown("send_message"))
        assertFalse("access_passwords is not canonical", CanonicalToolRegistry.isKnown("access_passwords"))
        assertFalse("install_app is not canonical", CanonicalToolRegistry.isKnown("install_app"))
        assertFalse("bogus tool is not canonical", CanonicalToolRegistry.isKnown("bogus_xyz"))
        assertNull("schemaFor unknown tool is null", CanonicalToolRegistry.schemaFor("bogus_xyz"))
    }

    @Test
    fun registryMissingRequiredArgumentDetection() {
        // create_note requires [title, content]; tags is optional.
        val cn = CanonicalToolRegistry.schemaFor("create_note")!!
        assertEquals(
            "create_note required params are title+content",
            listOf("title", "content"),
            cn.requiredParams.map { it.name })

        // No args → both required args missing.
        assertEquals(listOf("title", "content"), missingRequiredArgs("create_note", emptySet()))
        // Only title → content missing.
        assertEquals(listOf("content"), missingRequiredArgs("create_note", setOf("title")))
        // All required present → nothing missing (extra optional tags ignored).
        assertEquals(emptyList<String>(), missingRequiredArgs("create_note", setOf("title", "content")))
        assertEquals(emptyList<String>(), missingRequiredArgs("create_note", setOf("title", "content", "tags")))

        // delete_all_notes takes no args → never a missing-arg failure.
        assertTrue("delete_all_notes has no required params",
            CanonicalToolRegistry.schemaFor("delete_all_notes")!!.requiredParams.isEmpty())
        assertEquals(emptyList<String>(), missingRequiredArgs("delete_all_notes", emptySet()))
    }

    // --- 3. SafetyPipeline decision (with device Context) ---
    @Test
    fun pipelineBlocksAndRequiresConfirmationCorrectly() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pipeline = SafetyPipeline(ctx, guard)

        // isBlocked: only BLOCK.
        assertTrue(pipeline.isBlocked(RiskLevel.BLOCK))
        assertFalse(pipeline.isBlocked(RiskLevel.STRONG_CONFIRM))
        assertFalse(pipeline.isBlocked(RiskLevel.CONFIRM))
        assertFalse(pipeline.isBlocked(RiskLevel.DIRECT))

        // requiresConfirmation: CONFIRM and STRONG_CONFIRM, not BLOCK, not DIRECT.
        assertTrue(pipeline.requiresConfirmation(RiskLevel.CONFIRM))
        assertTrue(pipeline.requiresConfirmation(RiskLevel.STRONG_CONFIRM))
        assertFalse(pipeline.requiresConfirmation(RiskLevel.BLOCK))
        assertFalse(pipeline.requiresConfirmation(RiskLevel.DIRECT))

        // classifyRisk combines tool + input (max tier).
        assertEquals(RiskLevel.BLOCK, pipeline.classifyRisk("make_payment", ""))
        assertEquals(RiskLevel.STRONG_CONFIRM, pipeline.classifyRisk("delete_all_notes", ""))
        assertEquals(RiskLevel.STRONG_CONFIRM, pipeline.classifyRisk("delete_all_notes", "delete all notes"))
        // payment mention in input escalates even an ordinary tool to BLOCK
        assertEquals(RiskLevel.BLOCK, pipeline.classifyRisk("open_url", "open the bank payment page"))

        assertTrue("confirmation message must be non-blank",
            pipeline.confirmationMessage("delete_all_notes", RiskLevel.STRONG_CONFIRM).isNotBlank())
    }

    /** Replicates the brain's missing-required-argument check against the canonical schema. */
    private fun missingRequiredArgs(tool: String, argKeys: Set<String>): List<String> =
        CanonicalToolRegistry.schemaFor(tool)?.requiredParams
            ?.filter { it.name !in argKeys }
            ?.map { it.name }
            ?: emptyList()
}