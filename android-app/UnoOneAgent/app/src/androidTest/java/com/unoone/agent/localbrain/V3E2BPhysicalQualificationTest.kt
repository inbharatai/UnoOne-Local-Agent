package com.unoone.agent.localbrain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unoone.agent.core.device.*
import com.unoone.agent.core.model.BrainModelRegistry
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Real LiteRT-LM qualification, not an assumption-skipping smoke test. No runtime doubles. */
@RunWith(AndroidJUnit4::class)
class V3E2BPhysicalQualificationTest {
    private val runtime = LocalBrain()
    private var originalGate = true

    @Before fun loadExactE2B(): Unit = runBlocking {
        originalGate = AgentRuntimeGate.isEnabled()
        check(originalGate) { "QUALIFICATION BLOCKED: operator must explicitly enable the agent first" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spec = BrainModelRegistry.GEMMA_4_E2B
        val path = ModelManager(context).getLlmModelPath(spec, forceVerify = true)
        assertNotNull("QUALIFICATION FAILED: exact integrity-verified E2B missing/invalid. Import ${spec.modelFolder}/${spec.fileName}; no skip or alternate model allowed", path)
        val result = runtime.loadModel(requireNotNull(path), spec)
        assertTrue("E2B native load failed: $result; ${runtime.lastLoadError()}", result is Result.Success)
        assertTrue(runtime.isModelLoaded())
        assertEquals(spec.id, runtime.loadedProfile()?.id)
        assertTrue("Actual backend must be recorded", runtime.activeBackend().isNotBlank())
        Log.i("V3Qualification", "profile=${spec.manifestId}; actualBackend=${runtime.activeBackend()}; forcedIntegrity=true")
    }

    @After fun closeRuntime() = runBlocking {
        try { assertTrue("Native unload not acknowledged; qualification fails closed", runtime.unloadModel()) }
        finally { AgentRuntimeGate.setEnabled(originalGate) }
    }

    @Test fun realTextInference() = runBlocking {
        val text = success(runtime.chat("Reply with the single word READY. Do not call tools."))
        assertTrue("Empty native text response", text.isNotBlank())
        assertTrue("Instruction adherence proxy failed: $text", text.contains("READY", ignoreCase = true))
    }

    @Test fun actualPixelImageContentInference() = runBlocking {
        val bitmap = V3PixelFixture.bitmap()
        try {
            val answer = success(runtime.describeSceneWithVision(V3PixelFixture.png(bitmap),
                "Identify the two colored shapes, their left/right positions, and the visible label."))
            val lower = answer.lowercase()
            assertTrue("Missing fixture color; weak semantic proxy only: $answer", "red" in lower && "blue" in lower)
            assertTrue("Missing fixture label; weak semantic proxy only: $answer", "search" in lower)
        } finally { bitmap.recycle() }
    }

    @Test fun nativeControllerParsesExactlyOneIssuedAction() = runBlocking {
        val state = V3PixelFixture.state()
        val brain = GemmaE2BBrain(runtime, clockMs = SystemClock::elapsedRealtime)
        val action = brain.plan(DevicePlanRequest(
            "Propose one ClickNode on the issued node labeled Search. Do not execute it.", state, "", 0))
        assertEquals(DeviceAction.ClickNode(state.snapshot.id, "search"), action)
        // The adapter already decoded and validated native JSON; roundtrip checks public schema.
        assertEquals(action, DeviceActionCodec.decode(DeviceActionCodec.encode(action), state, SystemClock.elapsedRealtime()))
    }

    @Test fun realImageGroundingMatchesIssuedSearchNode() = runBlocking {
        val bitmap = V3PixelFixture.bitmap()
        try {
            val bytes = V3PixelFixture.png(bitmap)
            val state = V3PixelFixture.state()
            val requested = SystemClock.elapsedRealtimeNanos()
            val captured = SystemClock.elapsedRealtimeNanos().coerceAtLeast(requested + 1)
            val envelope = SnapshotImageEnvelope(bytes, state.snapshot.id, state.snapshot.eventSequence,
                state.snapshot.displayBounds, state.snapshot.displayBounds, requested, captured, 1, 0, NativeImageReviewReceipt(true, true, SnapshotImageEnvelope.digest(bytes), state.snapshot.id,
                    com.unoone.agent.core.device.UiStateHasher.hash(state.snapshot), state.snapshot.windows.map { it.packageName }.toSet(),
                    state.snapshot.eventSequence, 1, 0, captured / 1_000_000))
            val brain = GemmaE2BBrain(runtime, SnapshotImageProvider { id ->
                if (id == state.snapshot.id) envelope else null
            }, SystemClock::elapsedRealtime)
            assertTrue(brain.capabilities.vision)
            val grounded = brain.groundTarget("The blue circle on the right labeled Search; locate the circle's full bounding box", state)
            assertEquals("search", grounded.nodeId)
            assertNull(grounded.visualTargetId)
        } finally { bitmap.recycle() }
    }

    @Test fun disabledLoadedRuntimeRejectsTextAndImage() = runBlocking {
        AgentRuntimeGate.setEnabled(false)
        runtime.cancelInference("V3 operator master gate test")
        assertTrue(runtime.chat("Reply READY") is Result.Error)
        val bitmap = V3PixelFixture.bitmap()
        try { assertTrue(runtime.describeSceneWithVision(V3PixelFixture.png(bitmap), "Describe") is Result.Error) }
        finally { bitmap.recycle() }
        // Idle cancellation is covered here; this is NOT proof of cancellation of active JNI work.
    }

    private fun <T> success(result: Result<T>): T = when (result) {
        is Result.Success -> result.data
        is Result.Error -> throw AssertionError("Real native inference failed: ${result.message}")
        else -> throw AssertionError("Real native inference did not complete: $result")
    }
}

/** Synthetic public pixels only; never captures a device screen or accesses user data. */
internal object V3PixelFixture {
    val display = RectData(0, 0, 640, 360)
    val searchBounds = RectData(420, 100, 580, 260)
    fun bitmap(): Bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED
        canvas.drawRect(60f, 100f, 220f, 260f, paint)
        paint.color = Color.BLUE
        canvas.drawCircle(500f, 180f, 80f, paint)
        paint.color = Color.WHITE
        paint.textSize = 30f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Search", 500f, 190f, paint)
    }
    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
    }
    fun state(): PerceptionState {
        val node = UiNode("search", 1, "0/1", "com.unoone.agent.fixture", "android.widget.Button",
            text = "Search", bounds = searchBounds, clickable = true, semantic = TargetSemantic.NAVIGATION)
        return PerceptionState(UiSnapshot("v3-synthetic", SystemClock.elapsedRealtime(), 1, display,
            listOf(UiWindow(1, "com.unoone.agent.fixture", display, listOf(node)))))
    }
}
