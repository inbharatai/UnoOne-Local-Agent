package com.unoone.agent.owl

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.GuiOwlArtifact
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.localbrain.LocalBrain
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.storage.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit physical qualification only. Missing models/consent FAIL, never assume/skip.
 * Uses the application's resident runtime; never creates, replaces or unloads a runtime.
 */
@RunWith(AndroidJUnit4::class)
class OwlPhysicalQualificationTest {
    @Test fun selectedResidentPairRealTextAndSyntheticGui(): Unit = runBlocking(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<UnoOneApplication>()
        check(InstrumentationRegistry.getArguments().getString("owlPhysicalConsent") == "true") {
            "Explicit operator owlPhysicalConsent=true required; no automatic model acquisition"
        }
        check(AgentRuntimeGate.isEnabled() && app.brainProviderPreferences.owlOptIn)
        val spec = BrainModelRegistry.GUI_OWL_1_5_4B_INSTRUCT
        assertEquals(GuiOwlArtifact.MANIFEST_ID, PreferencesManager(app).selectedBrainManifestId)
        val manager = ModelManager(app)
        val descriptor = requireNotNull(manager.findModel(spec.manifestId))
        assertEquals(setOf(GuiOwlArtifact.DECODER, GuiOwlArtifact.PROJECTOR), descriptor.files.map { it.name }.toSet())
        assertTrue("FAIL: user must install the complete pinned pair; no download, skip or fallback", manager.modelHealth(spec.manifestId, forceVerify = true).verified)
        val orchestrator = app.orchestrator
        assertEquals(spec, orchestrator.loadedBrainProfile())
        assertTrue("Operator must load selected Owl first", orchestrator.isPhoneBrainResident())
        // Read-only instrumentation inspection: same resident object, not a second runtime.
        val field = orchestrator.javaClass.getDeclaredField("localBrain").apply { isAccessible = true }
        val resident = field.get(orchestrator) as LocalBrain
        val receipt = requireNotNull(resident.nativeConfigReceipt())
        val policy = receipt.split(';').mapNotNull { item -> item.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        assertEquals("2", policy["cpu"])
        assertEquals("2048", policy["ctx"])
        assertEquals("256", policy["output"])
        assertEquals("false", policy["gpu"])
        assertEquals("false", policy["hostReduced"])
        assertTrue(resident.supportsImages())
        val path = orchestrator.loadedBrainPath()
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        // Coroutine timeout alone cannot interrupt blocking JNI. Independent cancellation reaches nativeCancel.
        val stop = watchdog.schedule({ orchestrator.cancelLlmInference("Owl qualification 210-second budget expired") }, 210, TimeUnit.SECONDS)
        try {
            val result = withTimeout(220_000) { orchestrator.runOwlSelfTest(spec) }
            Log.i("OwlQualification", "manifest=${result.manifestId}; fullHash=true; receipt=$receipt; ${result.message}; probes=${result.probes}")
            assertEquals(listOf("text-2-plus-2", "synthetic-search-png"), result.probes.map { it.id })
            assertTrue(result.message, result.probes.all { it.passed } && result.toolAccepted)
            assertTrue("Two probes exceeded qualification budget", result.elapsedMs < 220_000)
            assertEquals(path, orchestrator.loadedBrainPath())
            assertEquals(receipt, resident.nativeConfigReceipt())
            orchestrator.cancelLlmInference("Owl qualification idle native stop check")
            assertTrue("Idle stop must retain resident resources; stop is not unload ACK", orchestrator.isPhoneBrainResident())
            assertEquals(receipt, resident.nativeConfigReceipt())
        } finally {
            orchestrator.cancelLlmInference("Owl qualification finished; no further generation")
            stop.cancel(false)
            watchdog.shutdownNow()
            // No release, close, forced reset or recovery: application retains ownership.
        }
    }
}
