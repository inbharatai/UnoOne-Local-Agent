package com.unoone.agent

import com.unoone.agent.owl.OwlSelfTest
import org.junit.Assert.*
import org.junit.Test

/** Codec/bbox policy fixtures only. NOT model inference or device qualification. */
class OwlSelfTestPolicyTest {
    private fun click(x: Int, y: Int) = "Action: Click Search.\n<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinate\":[$x,$y]}}</tool_call>"
    @Test fun nativeExpectedSearchBoxAcceptsCenterRejectsElsewhere() {
        assertTrue(OwlSelfTest.searchPointAccepted(click(500, 625)))
        assertFalse(OwlSelfTest.searchPointAccepted(click(100, 100)))
    }
    @Test fun malformedOutputCannotPass() {
        assertTrue(runCatching { OwlSelfTest.searchPointAccepted("Search at 500,625") }.isFailure)
    }
}
