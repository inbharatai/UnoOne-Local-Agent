package com.unoone.agent.owl

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.unoone.agent.brain.BrainSelfTestProbe
import com.unoone.agent.brain.BrainSelfTestResult
import com.unoone.agent.core.guiowl.OwlOutputCodec
import com.unoone.agent.core.guiowl.OwlPromptBuilder
import com.unoone.agent.core.model.BrainModelSpec
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream

/** Real runtime probes, called only inside the orchestrator's shared model lease. No capture/actions. */
object OwlSelfTest {
    internal fun searchPointAccepted(raw: String): Boolean {
        val proposal = OwlOutputCodec.decode(raw)
        val point = proposal.coordinate ?: return false
        // Native expected Search bbox: pixels [64,128..192,192] on 256 square PNG.
        return proposal.action == "click" && point.x in 250.0..750.0 && point.y in 500.0..750.0
    }

    suspend fun run(spec: BrainModelSpec, backend: String,
        request: suspend (String, String, ByteArray?) -> String): BrainSelfTestResult {
        val start = SystemClock.elapsedRealtime()
        val probes = mutableListOf<BrainSelfTestProbe>()
        suspend fun probe(id: String, block: suspend () -> Boolean) {
            val t = SystemClock.elapsedRealtime()
            val passed = try { block() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
            probes += BrainSelfTestProbe(id, "synthetic runtime probe", null, passed,
                "${if (passed) "passed" else "failed"}; ${SystemClock.elapsedRealtime() - t} ms")
        }
        probe("text-2-plus-2") {
            request("Answer the arithmetic question with only its numeral.", "2+2", null).trim() == "4"
        }
        probe("synthetic-search-png") {
            val bitmap = createBitmap(256, 256)
            val bytes = try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13f }
                canvas.drawText("SYNTHETIC GUI TEST", 16f, 32f, paint)
                paint.color = Color.BLUE
                canvas.drawRect(64f, 128f, 192f, 192f, paint)
                paint.color = Color.WHITE; paint.textSize = 24f
                canvas.drawText("Search", 87f, 169f, paint)
                ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
            } finally { bitmap.recycle() }
            try {
                val prompt = OwlPromptBuilder.build("Click the center of the Search button.", "Synthetic GUI runtime test; no real device screenshot or user data.")
                searchPointAccepted(request(prompt.system, prompt.user, bytes))
            } finally { bytes.fill(0) }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        val passed = probes.count { it.passed }
        return BrainSelfTestResult(spec.manifestId, spec.displayName, true, true, backend, "", null,
            passed == probes.size, probes, elapsed,
            "Owl runtime self-test: $passed passed, ${probes.size - passed} failed in $elapsed ms. Synthetic PNG only; not device-workflow qualification or factual-accuracy evidence.")
    }
}
