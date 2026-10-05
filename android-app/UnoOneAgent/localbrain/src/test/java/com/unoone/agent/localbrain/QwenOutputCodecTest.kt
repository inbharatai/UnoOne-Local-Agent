package com.unoone.agent.localbrain

import com.unoone.agent.core.agent.SafetyVerdict
import com.unoone.agent.core.model.Result
import org.junit.Assert.*
import org.junit.Test

/** Pure output contract tests ONLY; these do not load JNI or claim model/device accuracy. */
class QwenOutputCodecTest {
    @Test fun canonicalWholeObjectAccepted() {
        assertTrue(QwenOutputCodec.tool("""{"tool":"read_screen","args":{}}""") is Result.Success)
    }
    @Test fun rejectsSurroundingProseTruncationArraysAndExtraKeys() {
        listOf("prefix {\"tool\":\"read_screen\",\"args\":{}}", "{\"tool\":\"read_screen\"}",
            "[{\"tool\":\"read_screen\",\"args\":{}}]",
            "{\"tool\":\"read_screen\",\"args\":{},\"extra\":true}",
            "{\"tool\":\"read_screen\",\"args\":{}} trailing").forEach {
            assertTrue(it, QwenOutputCodec.tool(it) is Result.Error)
        }
    }
    @Test fun rejectsUnknownToolArgumentAndDisallowedRoute() {
        assertTrue(QwenOutputCodec.tool("""{"tool":"invented","args":{}}""") is Result.Error)
        assertTrue(QwenOutputCodec.tool("""{"tool":"read_screen","args":{"extra":1}}""") is Result.Error)
        assertTrue(QwenOutputCodec.tool("""{"tool":"read_screen","args":{}}""", setOf("open_app")) is Result.Error)
    }
    @Test fun verdictIsExactEnum() {
        assertEquals(SafetyVerdict.UNSAFE, QwenOutputCodec.verdict("""{"verdict":"UNSAFE"}"""))
        assertTrue(runCatching { QwenOutputCodec.verdict("""{"verdict":"SAFE because"}""") }.isFailure)
        assertTrue(runCatching { QwenOutputCodec.verdict("""{"verdict":"SAFE","extra":1}""") }.isFailure)
    }
}
