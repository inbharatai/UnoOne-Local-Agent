package com.unoone.agent.execution

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.accessibilitycontrol.AccessibilityControl
import com.unoone.agent.agentrouter.AgentRouter
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.ToolCall
import com.unoone.agent.phonecontrol.CalendarControl
import com.unoone.agent.phonecontrol.OcrControl
import com.unoone.agent.phonecontrol.PhoneControl
import com.unoone.agent.storage.db.UnoOneDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies every tool the Gemma planner can emit (declared in UnoOneToolSet) dispatches to a real
 * [ActionExecutor] branch — never falling through to [AgentRouter] (which returns "Unknown tool:
 * … No handler registered"). Also guards the `deactivate_blind_aid` typo regression and proves the
 * note tools actually persist/search/delete through the real Room DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionExecutorToolCoverageTest {

    private lateinit var db: UnoOneDatabase
    private lateinit var executor: ActionExecutor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnoOneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        executor = ActionExecutor(
            context = context,
            noteDao = db.noteDao(),
            skillDao = db.skillDao(),
            memoryDao = db.memoryDao(),
            actionLogDao = db.actionLogDao(),
            phoneControl = PhoneControl(context),
            calendarControl = CalendarControl(context),
            ocrControl = OcrControl(context),
            accessibilityControl = AccessibilityControl(),
            agentRouter = AgentRouter()
        )
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun everyToolDispatchesToARealBranchNotAgentRouter() {
        for ((tool, args) in toolCalls()) {
            val result = runBlocking { executor.executeTool(ToolCall(tool, args)) }
            assertFalse(
                "Tool '$tool' fell through to AgentRouter (no real branch): ${messageOf(result)}",
                isRouterFallback(result)
            )
        }
    }

    @Test
    fun deactivateBlindAidIsHandledAndReturnsSuccess() {
        // Regression for the prior `deactivate_blind_id` typo: the correct tool name must resolve.
        val result = runBlocking { executor.executeTool(ToolCall("deactivate_blind_aid", JsonObject(emptyMap()))) }
        assertTrue("deactivate_blind_aid must be a handled branch", result is Result.Success)
        assertEquals("Blind Aid deactivated.", (result as Result.Success).data)
    }

    @Test
    fun prepareDocumentFillOpensOnlySupportedOfflinePickers() {
        var opened: String? = null
        executor._prepareDocumentFill = { opened = it }

        val success = runBlocking {
            executor.executeTool(ToolCall("prepare_document_fill", obj { put("format", "DOCX") }))
        }
        assertTrue(success is Result.Success)
        assertEquals("docx", opened)

        opened = null
        val rejected = runBlocking {
            executor.executeTool(ToolCall("prepare_document_fill", obj { put("format", "doc") }))
        }
        assertTrue(rejected is Result.Error)
        assertEquals(null, opened)
    }

    @Test
    fun noteToolsPersistSearchAndDeleteEndToEnd() {
        runBlocking {
            val created = executor.executeTool(ToolCall("create_note", buildJsonObject {
                put("title", "Meeting Notes")
                put("content", "Discuss the roadmap and deadlines")
            }))
            assertTrue(created is Result.Success)

            val found = executor.executeTool(ToolCall("search_notes", buildJsonObject {
                put("query", "Meeting")
            }))
            assertTrue("search_notes should find the created note", found is Result.Success)
            assertTrue((found as Result.Success).data.contains("Meeting Notes"))

            val deleted = executor.executeTool(ToolCall("delete_notes", buildJsonObject {
                put("query", "Meeting")
            }))
            assertTrue(deleted is Result.Success)
            assertTrue((deleted as Result.Success).data.contains("Deleted 1 note"))

            val after = executor.executeTool(ToolCall("search_notes", buildJsonObject {
                put("query", "Meeting")
            }))
            assertTrue(after is Result.Success)
            assertFalse((after as Result.Success).data.contains("Meeting Notes"))
        }
    }

    @Test
    fun summarizeTextReturnsCondensedText() {
        val longText = "This is the first sentence about a long topic. " +
            "Here is a second sentence with more detail and content words. " +
            "A third sentence adds extra context for the summarizer to consider. " +
            "Finally a fourth sentence wraps up the discussion nicely enough."
        val result = runBlocking {
            executor.executeTool(ToolCall("summarize_text", buildJsonObject { put("text", longText) }))
        }
        assertTrue(result is Result.Success)
        val summary = (result as Result.Success).data
        assertTrue("Summary must be shorter than the input", summary.length < longText.length)
        assertTrue("Summary must be non-empty", summary.isNotBlank())
    }

    private fun isRouterFallback(result: Result<String>): Boolean =
        result is Result.Error && result.message.contains("No handler registered")

    private fun messageOf(result: Result<String>): String =
        if (result is Result.Error) result.message else "Success"

    /** One minimal-valid arg set per UnoOneToolSet @Tool method. */
    private fun toolCalls(): List<Pair<String, JsonObject>> = listOf(
        "create_note" to obj { put("title", "T"); put("content", "C") },
        "search_notes" to obj { put("query", "x") },
        "summarize_text" to obj { put("text", "A long enough sentence to summarize here.") },
        "speak_response" to obj { put("text", "hi") },
        "open_chrome" to JsonObject(emptyMap()),
        "open_app" to obj { put("app_name", "chrome"); put("package_name", "com.android.chrome") },
        "open_url" to obj { put("url", "https://example.com") },
        "open_camera" to JsonObject(emptyMap()),
        "system_control" to obj { put("action", "go_home") },
        "read_screen" to JsonObject(emptyMap()),
        "ocr_screen" to JsonObject(emptyMap()),
        "create_skill" to obj { put("name", "S"); put("steps", "a|b") },
        "draft_email" to obj { put("to", "a@b.com"); put("subject", "s"); put("body", "b") },
        "send_whatsapp" to obj { put("number", "+919999999999"); put("message", "hi") },
        "check_calendar" to JsonObject(emptyMap()),
        "open_calendar" to JsonObject(emptyMap()),
        "open_calendar_insert" to obj { put("title", "Meeting") },
        "open_dialer" to obj { put("number", "911") },
        "share_text" to obj { put("text", "hi") },
        "delete_notes" to obj { put("query", "x") },
        "delete_all_notes" to JsonObject(emptyMap()),
        "export_data" to JsonObject(emptyMap()),
        "detect_objects" to JsonObject(emptyMap()),
        "deactivate_blind_aid" to JsonObject(emptyMap()),
        // voice_recording with no _recordVoiceNote wired returns a handled Result.Error (not a
        // router fallback); web_search in Robolectric (no network) returns the offline message.
        "voice_recording" to obj { put("duration_seconds", 2) },
        "web_search" to obj { put("query", "weather") },
        // describe_scene without MediaProjection permission returns a handled permission Error
        // (not a router fallback); with permission it builds the OCR + context description.
        "describe_scene" to obj { put("aspect", "any buttons") },
        // secure_browser_task: the origin resolves (approved), but no _openSecureBrowserTask runner
        // is wired in this unit test → a handled "Secure Browser is not available" Result.Error
        // (NOT a router fallback). A non-approved origin would return the "not approved" error.
        "secure_browser_task" to obj { put("origin", "unigurus"); put("task", "fill the form") },
        "prepare_document_fill" to obj { put("format", "pdf") }
    )

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(build)
}
