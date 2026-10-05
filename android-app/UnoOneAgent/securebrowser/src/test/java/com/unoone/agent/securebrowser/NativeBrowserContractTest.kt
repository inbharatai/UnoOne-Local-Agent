package com.unoone.agent.securebrowser

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeBrowserContractTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun literalPlannerShapes() {
        assertEquals(false, NativeBrowserContract.normalize("toggle_checkbox", args("""{"index":1}"""), """{"checked":true}""")["checked"]!!.jsonPrimitive.boolean)
        assertEquals("2026-01-02", NativeBrowserContract.normalize("pick_date", args("""{"index":1,"date":"2026-01-02"}"""), null)["date"]!!.jsonPrimitive.content)
        assertEquals(false, NativeBrowserContract.normalize("scroll", args("""{"index":1,"down":false,"pixels":120,"num_pages":1}"""), null)["down"]!!.jsonPrimitive.boolean)
        assertEquals(80, NativeBrowserContract.normalize("scroll_horizontally", args("""{"index":1,"right":false,"pixels":80}"""), null)["pixels"]!!.jsonPrimitive.int)
    }
    @Test fun invalidValuesFailClosed() {
        for (value in listOf("", "2026-02-31", "tomorrow")) {
            assertTrue(runCatching { NativeBrowserContract.normalize("pick_date", buildJsonObject { put("index", 1); put("date", value) }, null) }.isFailure)
        }
        assertTrue(runCatching { NativeBrowserContract.normalize("toggle_checkbox", args("""{"index":1}"""), null) }.isFailure)
        assertTrue(runCatching { NativeBrowserContract.normalize("scroll", args("""{"down":false,"pixels":99999}"""), null) }.isFailure)
    }
}
