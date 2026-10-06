package com.unoone.agent.localbrain

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.model.*
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.localbrain.qwen.QwenMnnRuntime
import com.unoone.agent.modelmanager.ModelManager
import com.unoone.agent.modelmanager.QwenMnnArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Opt-in physical Android/JNI smoke qualification; never a host or GUI accuracy benchmark.
 * Missing/invalid models FAIL this class. Ordinary CI assembles the test APK, not this suite.
 * One method deliberately amortizes one multi-GB native load across all probes.
 */
@RunWith(AndroidJUnit4::class)
class QwenMnnPhysicalQualificationTest {
    @Test fun installedPinnedModelActualJniSuite(): Unit = runBlocking(Dispatchers.IO) {
        com.unoone.agent.task.ModelTransitions.run {
        check(AgentRuntimeGate.isEnabled()) { "Operator must explicitly enable UnoOne first" }
        val app = ApplicationProvider.getApplicationContext<UnoOneApplication>()
        check(app.brainProviderPreferences.qwenOptIn) { "Operator must explicitly opt in to experimental Qwen first" }
        val manager = ModelManager(app)
        val spec = BrainModelRegistry.QWEN3_5_2B
        assertEquals("qwen3.5-2b-mnn", spec.manifestId)
        assertFalse(spec.isDeviceVerified)
        assertTrue(spec.experimentalLabel.orEmpty().contains("EXPERIMENTAL"))
        assertEquals("35781816d7b6a9dcb273a6765ac9563401951c3c", QwenMnnArtifact.REVISION)
        val descriptor = requireNotNull(manager.findModel(spec.manifestId)) { "Pinned manifest missing" }
        assertEquals(spec.modelFolder, descriptor.folder)
        assertEquals(9, descriptor.files.size)
        assertEquals(QwenMnnArtifact.files.sortedBy { it.name }, descriptor.files.sortedBy { it.name })
        assertTrue("QUALIFICATION FAILED: install all nine pinned Qwen files; full SHA-256 verification required, never skip",
            manager.modelHealth(spec.manifestId, forceVerify = true).verified)
        val folder = requireNotNull(manager.getMnnModelFolder(spec)) { "Verified Qwen folder unavailable; no fallback" }
        val config = File(folder, spec.fileName)
        assertTrue(config.isFile)

        val owner = "qwen-physical-qualification"
        val gate = E4bRuntimeCoordinator
        val orchestrator = app.orchestrator
        val runtime = QwenMnnRuntime()
        check(ExclusiveBrainLeaseState.acquire(owner)) { "Global native lease busy or quarantined; no reset allowed" }
        val authorization = com.unoone.agent.task.PhoneModelRestoreAuthorization.capture()
        val priorPath = orchestrator.loadedBrainPath()
        val priorProfile = orchestrator.loadedBrainProfile()
        var unloadAcknowledged = false
        var claimed = false
        var cleanupAcknowledged = false
        try {
            orchestrator.cancelLlmInference("explicit Qwen physical qualification")
            unloadAcknowledged = orchestrator.unloadLlmModel()
            check(unloadAcknowledged && !orchestrator.isPhoneBrainResident()) { "App-owned native unload ACK required" }
            gate.operationMutex.withLock {
                check(gate.claimAllocation(runtime, owner, owner)) { "Global native allocation claim refused" }
                claimed = true
                try {
                    val receiptText = runtime.load(config.absolutePath, contextLimit = 2048, maxOutput = 256)
                    val json = Json { isLenient = false; ignoreUnknownKeys = false }
                    val receipt = json.parseToJsonElement(receiptText).jsonObject
                    for (policy in listOf(receipt, receipt.getValue("mllm").jsonObject)) {
                        assertEquals("cpu", policy.getValue("backend_type").jsonPrimitive.content)
                        assertEquals(2, policy.getValue("thread_num").jsonPrimitive.int)
                    }
                    assertEquals(256, receipt.getValue("max_new_tokens").jsonPrimitive.int)
                    assertFalse(receipt.getValue("jinja").jsonObject.getValue("context").jsonObject
                        .getValue("enable_thinking").jsonPrimitive.boolean)
                    assertEquals(2048, receipt.getValue("unoone_admission").jsonObject
                        .getValue("decoder_context_tokens").jsonPrimitive.int)
                    assertEquals(receiptText, runtime.resolvedConfigReceipt)
                    assertTrue("Pinned export must expose its actual image processor", runtime.imageSupported)
                    Log.i("QwenQualification", "manifest=${spec.manifestId}; forcedFullHash=true; backend=${runtime.backend}; receipt=$receiptText")

                    val text = runtime.generate("Answer with only the number, no explanation.", "What is 2 + 2?", tokenLimit = 32)
                    assertEquals("Actual text arithmetic probe: $text", "4", text.trim())
                    val output = runtime.generate("Return only valid JSON, no markdown or explanation.",
                        "What is 2 + 2? Return an object with exactly one key named sum and its integer value.", tokenLimit = 64)
                    // Parse actual native output; tolerate JSON whitespace, never fences or extracted substrings.
                    val value = json.parseToJsonElement(output).jsonObject
                    assertEquals(setOf("sum"), value.keys)
                    assertEquals(JsonPrimitive(4), value.getValue("sum"))

                    val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
                    val png = try {
                        bitmap.eraseColor(Color.RED)
                        ByteArrayOutputStream().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                            stream.toByteArray()
                        }
                    } finally { bitmap.recycle() }
                    val color = runtime.generate("Answer with one English color word only.",
                        "What is the solid color of this image?", imageBytes = png, tokenLimit = 32)
                    assertEquals("Actual synthetic-pixel image probe: $color", "red",
                        color.trim().trimEnd('.').trim().lowercase(Locale.ROOT))

                    val stale = runtime.admitRequest()
                    runtime.cancel()
                    val rejection = runCatching {
                        runtime.generate("Answer briefly.", "Reply READY.", tokenLimit = 32, permit = stale)
                    }.exceptionOrNull()
                    assertTrue("Idle cancellation must reject a previously admitted permit, not generate",
                        rejection is IllegalStateException && rejection.message == "Stale request permit")
                    // This proves stale admission rejection, NOT cancellation during active JNI generation.
                } finally {
                    cleanupAcknowledged = runCatching { runtime.close() }.getOrDefault(false)
                    if (cleanupAcknowledged) {
                        gate.acknowledgeClosed(runtime)
                        assertEquals("UNLOADED", runtime.status)
                    } else {
                        gate.quarantine(runtime) // Sticky: never force-reset the shared gate.
                    }
                    check(cleanupAcknowledged) { "Native close ACK missing: allocation/lease retained; restart required" }
                }
            }
        } finally {
            // Never restore or release after uncertain destruction. Preferences/files are never changed.
            if (unloadAcknowledged && (!claimed || cleanupAcknowledged)) {
                try {
                    if (priorPath != null && priorProfile != null) {
                        val restored = orchestrator.loadLlmModelUnderLease(priorPath, priorProfile, authorization)
                        check(restored is Result.Success) { "Prior exact profile restoration failed: $restored" }
                    }
                } finally {
                    check(ExclusiveBrainLeaseState.release(owner)) { "Lease release refused; preserve quarantine and restart" }
                }
            }
        }
        }
    }
}
