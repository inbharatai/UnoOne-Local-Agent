package com.unoone.agent.localbrain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real device-time smoke tests for the exact integrity-verified Gemma 4 E4B brain.
 *
 * These tests exercise the planner directly. In the application, deterministic routing should handle
 * simple commands before model inference; direct planning probes remain useful for proving that E4B
 * can produce canonical calls when the agent lane invokes it.
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
    fun verifiedE4BLoadsWhenPresent() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No integrity-verified E4B model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val result = planner.load(path!!)
        assert(result is Result.Success) {
            "Model load failed: ${(result as? Result.Error)?.message}"
        }
        assert(planner.loadedProfile()?.manifestId == "gemma-4-e4b")
        planner.close()
    }

    @Test
    fun openChromeCommandProducesCanonicalTool() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No integrity-verified E4B model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        assert(loadResult is Result.Success)

        val planResult = planner.plan(
            "Open Chrome",
            ContextSnapshot(currentPackage = "com.unoone.agent")
        )
        check(planResult is Result.Success) {
            "Planning failed: ${(planResult as? Result.Error)?.message}"
        }

        val toolCall = planResult.data
        assert(toolCall.tool == "open_chrome") {
            "Expected 'open_chrome' but got '${toolCall.tool}' with args ${toolCall.args}"
        }
        planner.close()
    }

    @Test
    fun createNoteCommandProducesRequiredArguments() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No integrity-verified E4B model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        val loadResult = planner.load(path!!)
        assert(loadResult is Result.Success)

        val planResult = planner.plan(
            "Create a note titled Shopping with content buy milk tomorrow",
            ContextSnapshot(currentPackage = "com.unoone.agent")
        )
        check(planResult is Result.Success) {
            "Planning failed: ${(planResult as? Result.Error)?.message}"
        }

        val toolCall = planResult.data
        assert(toolCall.tool == "create_note") {
            "Expected 'create_note' but got '${toolCall.tool}' with args ${toolCall.args}"
        }
        assert(toolCall.args["title"]?.toString()?.isNotBlank() == true)
        assert(toolCall.args["content"]?.toString()?.contains("milk", ignoreCase = true) == true)
        planner.close()
    }

    @Test
    fun routesHindiBlindStartAndMissingFieldsSafelyInSequence() = runBlocking {
        val path = modelManager.getLlmModelPath()
        assumeTrue("No integrity-verified E4B model found — skipping accuracy test", path != null)

        val planner = GemmaPlanner()
        assert(planner.load(path!!) is Result.Success)
        val snapshot = ContextSnapshot(currentPackage = "com.unoone.agent")
        val probes = listOf(
            "ब्लाइंड मोड चालू करो" to "detect_objects",
            "draft an email with subject status and body the build is ready" to "speak_response",
            "schedule a dentist appointment" to "speak_response",
            "open" to "speak_response",
            "Open Chrome" to "open_chrome"
        )

        probes.forEach { (command, expected) ->
            val result = planner.plan(command, snapshot)
            check(result is Result.Success) {
                "Planning '$command' failed: ${(result as? Result.Error)?.message}"
            }
            assert(result.data.tool == expected) {
                "Expected '$expected' for '$command' but got '${result.data.tool}'"
            }
        }
        planner.close()
    }

}
