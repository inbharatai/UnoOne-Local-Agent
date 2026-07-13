package com.unoone.agent.localbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.eval.EvalPromptSet
import com.unoone.agent.core.eval.EvalScorer
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-time calibration harness for the on-device brain.
 *
 * This is the "is Gemma actually good enough?" measurement tool: it loads whichever `.litertlm`
 * profile is present on the device, runs the fixed [EvalPromptSet] through [GemmaPlanner.plan] one
 * case at a time, scores tool + arg accuracy with the pure-JVM [EvalScorer], and **prints** a real
 * [com.unoone.agent.core.eval.EvalSummary] to logcat. Run it against the exact Gemma 4 E2B
 * artifact and record the backend, device and model hash with the result.
 *
 * This test does NOT gate on an accuracy threshold — a profile being evaluated is allowed to score
 * low; that low number is the point. It only asserts that every case produced a verdict (i.e. the
 * harness executed completely). Read the printed summary for the real verdict.
 *
 * To run it, push a model first:
 *   adb push /path/to/gemma-4-E2B-it.litertlm \
 *     /sdcard/Android/data/com.unoone.agent/files/models/brain/gemma-4-e2b/
 *
 * Then: ./gradlew :app:connectedDebugAndroidTest --tests *.BrainEvalHarnessTest
 * and read the `EvalSummary` block in the logcat / test report.
 */
class BrainEvalHarnessTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = ModelManager(context)
        modelManager.ensureModelDirectories()
    }

    @Test
    fun runsPromptSetAndReportsAccuracy() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No .litertlm model found — skipping eval harness", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        check(loadResult is Result.Success) {
            "Model load failed: ${(loadResult as? Result.Error)?.message}"
        }

        val snapshot = ContextSnapshot(currentPackage = "com.unoone.agent")
        val actuals = ArrayList<ToolCall?>(EvalPromptSet.cases.size)

        for (case in EvalPromptSet.cases) {
            val planResult = planner.plan(case.prompt, snapshot)
            val call: ToolCall? = when (planResult) {
                is Result.Success -> planResult.data
                is Result.Error -> {
                    println("Eval[${case.id}] planner error: ${planResult.message}")
                    null
                }
            }
            actuals.add(call)
        }

        planner.close()

        val summary = EvalScorer.scoreAll(EvalPromptSet.cases, actuals)
        // Printed to both stdout (test report) and logcat so a human reads real numbers, not a
        // pass/fail flag. Device-time-only — no fabricated Gemma results are ever committed.
        println("==== UNOONE BRAIN EVAL ====")
        println("Profile: ${planner.loadedProfile()?.displayName ?: "unknown"} | backend: ${planner.activeBackend()}")
        println(summary)
        println("==== END BRAIN EVAL ====")

        // The harness must score every case — that is the only thing it asserts. Accuracy is read
        // from the printed summary; it is intentionally not gated (see class KDoc).
        assert(summary.total == EvalPromptSet.cases.size) {
            "Harness scored ${summary.total} of ${EvalPromptSet.cases.size} cases"
        }
    }
}