package com.unoone.agent.core.guiowl

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Negative archived model evidence, not new inference or device execution. */
class RejectedRawOutputTest {
    private fun raw(experiment: String, name: String): String =
        File(System.getProperty("voice.evidence"), "$experiment/$name.output.txt").readText()

    @Test fun v1BothCandidatesRemainRejectedByProductionCodec() {
        for (case in listOf("A", "B")) {
            OwlOutputCodec.decode(raw("voice-prompt-ab", "baseline-$case"))
            assertTrue(runCatching { OwlOutputCodec.decode(raw("voice-prompt-ab", "candidate-$case")) }.isFailure)
        }
    }

    @Test fun v2UnsupportedFocusIsNotRepairedIntoClick() {
        val failed = raw("voice-prompt-v2", "candidate-F1")
        assertTrue(failed.contains("\"focus\""))
        assertTrue(runCatching { OwlOutputCodec.decode(failed) }.isFailure)
        for (case in listOf("N1", "N2", "F1", "W1"))
            OwlOutputCodec.decode(raw("voice-prompt-v2", "baseline-$case"))
        for (case in listOf("N1", "N2", "W1"))
            OwlOutputCodec.decode(raw("voice-prompt-v2", "candidate-$case"))
    }
}
