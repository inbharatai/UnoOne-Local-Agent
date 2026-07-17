package com.unoone.agent.languagepacks

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real on-device, HEADLESS language-pack repair/retain gate (DEVICE_VERIFICATION §5: "activation is
 * blocked before health passes", "shared ASR is retained while another language depends on it",
 * "remove/reinstall/repair works"). Exercises the app's own [LanguagePackManager] uninstall→state→
 * reinstall cycle on the Malayalam pack, which shares the `sherpa-asr-indic` model with the other
 * five Indic packs.
 *
 * Sequence: assume ml + hi are installed (Phase 6 prerequisite) → uninstall ml → assert ml
 * state.installed=false with its pack-specific `sherpa-tts-mal` missing but the shared
 * `sherpa-asr-indic` NOT missing (retained) and hi still healthy → reinstall ml → assert
 * healthy+verified. Restores the original installed state; re-downloads the ~114 MB Malayalam TTS
 * over WiFi (allow minutes).
 *
 * Run: am instrument -e class com.unoone.agent.languagepacks.LanguagePackRepairRetainTest ...
 */
class LanguagePackRepairRetainTest {

    @Test
    fun uninstallRetainsSharedAsrAndRepairReinstalls() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lpm = LanguagePackManager(ctx)
        val ml = "ml-IN-standard"

        // Prerequisite: Phase 6 installed all baseline packs. Skip honestly if not.
        assumeTrue("ml must be installed before the uninstall test (Phase 6 prerequisite)",
            lpm.state(ml)?.installed == true)
        assumeTrue("hi must be installed (shared-asr retention needs a dependent)",
            lpm.state("hi-IN-standard")?.installed == true)

        // --- uninstall Malayalam ---
        val un = lpm.uninstall(ml)
        assertTrue("uninstall must succeed: ${un.self}", un is LanguagePackOperationResult.Success)

        val afterUn = lpm.state(ml)
        assertTrue("ml state must exist after uninstall", afterUn != null)
        assertFalse("ml must NOT be installed after uninstall", afterUn!!.installed)
        assertFalse("ml must NOT be healthy after uninstall", afterUn.healthy)
        assertTrue("ml missing list must cite its pack-specific TTS (sherpa-tts-mal): ${afterUn.missingModelIds}",
            afterUn.missingModelIds.contains("sherpa-tts-mal"))
        assertFalse(
            "ml must NOT list the shared sherpa-asr-indic as missing (it is retained): ${afterUn.missingModelIds}",
            afterUn.missingModelIds.contains("sherpa-asr-indic"))

        // shared ASR retained: a still-installed dependent pack stays healthy
        val hi = lpm.state("hi-IN-standard")
        assertTrue(
            "hi must remain installed+healthy after ml uninstall (shared ASR retained): $hi",
            hi != null && hi.installed && hi.healthy)

        // --- reinstall (repair) Malayalam ---
        val re = lpm.install(ml)
        assertTrue("reinstall (repair) must succeed: ${re.self}", re is LanguagePackOperationResult.Success)

        val afterRe = lpm.state(ml)
        assertTrue(
            "ml must be installed+healthy+verified after repair: $afterRe",
            afterRe != null && afterRe.installed && afterRe.healthy && afterRe.verified)
    }
}

/** Local shim to expose the sealed result message without a public accessor. */
private val LanguagePackOperationResult.self: String
    get() = when (this) {
        is LanguagePackOperationResult.Success -> message
        is LanguagePackOperationResult.Failure -> message
    }
