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
 * Device-time calibration harness for the on-device E4B brain.
 *
 * It loads the exact integrity-verified model returned by [ModelManager], runs the fixed
 * [EvalPromptSet] through [GemmaPlanner.plan], scores tool and argument accuracy with the pure-JVM
 * [EvalScorer], and prints a real evaluation summary. The model id, file hash, device, OS and active
 * backend must be recorded with the output.
 *
 * This test does not fabricate or assume an accuracy result. It asserts only that the complete prompt
 * set was scored. Release thresholds are applied to the recorded summary during device qualification.
 *
 * Preferred installation is through UnoOne Model Status. For a controlled engineering import, the
 * exact file must be placed at:
 *
 * `/sdcard/Android/data/com.unoone.agent/files/models/brain/gemma-4-e4b/gemma-4-E4B-it.litertlm`
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
        assumeTrue("No integrity-verified E4B model found — skipping eval harness", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        check(loadResult is Result.Success) {
            "Model load failed: ${(loadResult as? Result.Error)?.message}"
        }

        val loadedProfile = planner.loadedProfile()?.displayName ?: "unknown"
        val loadedBackend = planner.activeBackend()
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
        println("==== UNOONE E4B BRAIN EVAL ====")
        println("Profile: $loadedProfile | backend: $loadedBackend")
        println(summary)
        println("==== END E4B BRAIN EVAL ====")

        assert(summary.total == EvalPromptSet.cases.size) {
            "Harness scored ${summary.total} of ${EvalPromptSet.cases.size} cases"
        }
    }
}
