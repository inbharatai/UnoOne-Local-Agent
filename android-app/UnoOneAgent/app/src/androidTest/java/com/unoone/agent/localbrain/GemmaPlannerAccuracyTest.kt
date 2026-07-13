package com.unoone.agent.localbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real device-time accuracy test for the Gemma 4 E2B brain.
 *
 * This test only runs when a `.litertlm` model file is present on the device.
 * It loads the model, sends a set of known commands, and verifies that the
 * returned tool name matches the expected action.
 *
 * To run this test, push a model first:
 *   adb push /path/to/gemma-4-E2B-it.litertlm \
 *     /sdcard/Android/data/com.unoone.agent/files/models/brain/gemma-4-e2b/
 */
class GemmaPlannerAccuracyTest {

    private lateinit var context: Context
    private lateinit var modelManager: ModelManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = ModelManager(context)
        modelManager.ensureModelDirectories()
    }

    @Test
    fun modelLoadsWhenPresent() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No .litertlm model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val result = planner.load(path!!)
        assert(result is com.unoone.agent.core.model.Result.Success) {
            "Model load failed: ${(result as? com.unoone.agent.core.model.Result.Error)?.message}"
        }
        planner.close()
    }

    @Test
    fun openChromeCommandProducesOpenChromeTool() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No .litertlm model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        assert(loadResult is com.unoone.agent.core.model.Result.Success)

        val planResult = planner.plan(
            "Open Chrome",
            ContextSnapshot(currentPackage = "com.unoone.agent")
        )
        check(planResult is com.unoone.agent.core.model.Result.Success) {
            "Planning failed: ${(planResult as? com.unoone.agent.core.model.Result.Error)?.message}"
        }

        val toolCall = planResult.data
        assert(toolCall.tool == "open_chrome") {
            "Expected 'open_chrome' but got '${toolCall.tool}' with args ${toolCall.args}"
        }
        planner.close()
    }

    @Test
    fun createNoteCommandProducesCreateNoteTool() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No .litertlm model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        assert(loadResult is com.unoone.agent.core.model.Result.Success)

        val planResult = planner.plan(
            "Remember to buy milk tomorrow",
            ContextSnapshot(currentPackage = "com.unoone.agent")
        )
        check(planResult is com.unoone.agent.core.model.Result.Success)

        val toolCall = planResult.data
        assert(toolCall.tool == "create_note") {
            "Expected 'create_note' but got '${toolCall.tool}' with args ${toolCall.args}"
        }
        planner.close()
    }
}
