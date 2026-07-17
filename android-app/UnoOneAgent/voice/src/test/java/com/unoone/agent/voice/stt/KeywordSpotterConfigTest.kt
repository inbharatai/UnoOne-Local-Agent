package com.unoone.agent.voice.stt

import com.unoone.agent.voice.WakePhrases
import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordSpotterConfigTest {

    @Test
    fun bundledWakeEntriesUseOnlyKnownEnglishModelTokens() {
        val tokenLines = listOf(
            "<blk> 0", "S 3", "O 24", "TEN 251",
            "▁TO 10", "▁ME 86", "▁ONE 126", "▁LI 166", "▁UN 180"
        )

        assertEquals(emptySet<String>(), missingKeywordTokens(WakePhrases.KWS_ENTRIES, tokenLines))
    }

    @Test
    fun unknownPlainWordIsRejectedBeforeNativeInitialization() {
        assertEquals(
            setOf("listen"),
            missingKeywordTokens(listOf("listen :1.5 #0.3"), listOf("▁LI 166", "S 3", "TEN 251"))
        )
    }
}
