package com.unoone.agent.voice.stt

import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptQualityTest {
    @Test
    fun goodEnglishHindiAndHinglishClearRetryThreshold() {
        listOf(
            "what is SAT",
            "एस ए टी क्या है",
            "uno blind mode chalu karo"
        ).forEach { transcript ->
            assertTrue(
                "$transcript should be usable",
                TranscriptQuality.score(transcript, sampleCount = 48_000) >= 0.6f
            )
        }
    }

    @Test
    fun emptyPunctuationAndTruncatedDecodesFailThreshold() {
        listOf("", ".", "x").forEach { transcript ->
            assertTrue(
                "$transcript should be retried",
                TranscriptQuality.score(transcript, sampleCount = 80_000) < 0.6f
            )
        }
    }

    @Test
    fun repeatedHallucinatedTranscriptFailsThreshold() {
        assertTrue(
            TranscriptQuality.score("tv tv tv tv", sampleCount = 96_000) < 0.6f
        )
    }
}
