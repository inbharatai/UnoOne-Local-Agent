package com.unoone.agent.localbrain

import com.unoone.agent.core.model.BrainRuntime
import com.unoone.agent.core.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No native load: unsupported lanes must not even initialize a fallback planner. */
class LocalBrainOwlDispatchTest {
    @Test fun owlGenericLanesRejectWithoutInitializingAnyPlanner() = runBlocking {
        val brain = LocalBrain()
        LocalBrain::class.java.getDeclaredField("selectedRuntime").apply {
            isAccessible = true
            set(brain, BrainRuntime.LLAMA_CPP)
        }
        val results = listOf(
            brain.chat("hello"),
            brain.draftText("draft a note"),
            brain.planNext("read_screen", "visible text"),
            brain.judgeSafety("read_screen", "{}", "read"),
            brain.describeSceneWithVision(byteArrayOf(), "scene"),
            brain.parseToolCall("{\"tool\":\"read_screen\",\"args\":{}}")
        )
        results.forEach { result ->
            assertTrue(result is Result.Error)
            assertTrue((result as Result.Error).message.contains("GUI-Owl does not support"))
        }
        listOf("planner", "qwen", "owl").forEach { name ->
            val delegate = LocalBrain::class.java.getDeclaredField(name + "\$delegate").apply {
                isAccessible = true
            }.get(brain) as Lazy<*>
            assertFalse("$name must remain lazy", delegate.isInitialized())
        }
    }
}
