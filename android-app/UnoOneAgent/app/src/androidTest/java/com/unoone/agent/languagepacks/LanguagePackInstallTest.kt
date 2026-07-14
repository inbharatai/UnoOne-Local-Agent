package com.unoone.agent.languagepacks

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.modelmanager.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real on-device language-pack install gate (README Phase C: "validate every baseline
 * checksum + extraction on clean device").
 *
 * Drives the app's OWN ModelInstaller over real network (WiFi) for each downloadable baseline
 * pack, so every artifact is created app-owned (no shell-ownership / SELinux gotcha), downloaded,
 * sha256+size verified, and (for archives) extracted. Then re-checks health+verification.
 *
 * Also asserts Assamese (as-IN-standard, downloadable=false, no requiredModelIds) correctly
 * refuses to install — qualifying that it is NOT silently active.
 *
 * Run: am instrument -e class com.unoone.agent.languagepacks.LanguagePackInstallTest ...
 * Downloads ~1 GB total; allow several minutes. Logs tagged UnoOneLang.
 */
class LanguagePackInstallTest {

    private val tag = "UnoOneLang"

    private val baselinePacks = listOf(
        "en-IN-base",
        "hi-IN-standard",
        "bn-IN-standard",
        "ta-IN-standard",
        "te-IN-standard",
        "kn-IN-standard",
        "ml-IN-standard"
    )

    @Test
    fun installAllBaselinePacksAndQualifyAssamese() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Pre-create model dirs app-owned (defensive; the brain-dir ownership lesson).
        ModelManager(context).ensureModelDirectories()

        val lpm = LanguagePackManager(context)
        val failures = mutableListOf<String>()

        Log.i(tag, "==== UNOONE LANG PACK INSTALL ====")
        Log.i(tag, "catalog packs: ${lpm.catalog().packs.map { it.id + "(dl=" + it.downloadable + ")" }}")

        for (packId in baselinePacks) {
            Log.i(tag, ">>> install $packId")
            val result = try {
                lpm.install(packId) { modelId, percent, msg ->
                    if (percent == 100) Log.i(tag, "  $packId: $modelId -> $msg")
                }
            } catch (t: Throwable) {
                com.unoone.agent.languagepacks.LanguagePackOperationResult.Failure("exception: ${t.javaClass.simpleName}: ${t.message}")
            }
            val state = lpm.state(packId)
            Log.i(tag, "<<< $packId result=${result.javaClass.simpleName} msg=${result.self}")
            Log.i(tag, "    state installed=${state?.installed} healthy=${state?.healthy} verified=${state?.verified} missing=${state?.missingModelIds} unhealthy=${state?.unhealthyModelIds} unverified=${state?.unverifiedModelIds}")

            val ok = result is LanguagePackOperationResult.Success &&
                state != null && state.installed && state.healthy && state.verified
            if (!ok) {
                failures += "$packId (result=$result, healthy=${state?.healthy}, verified=${state?.verified})"
            }
        }

        // Assamese must be disabled: install returns Failure, state is not installed.
        Log.i(tag, ">>> qualify Assamese (as-IN-standard) — must REFUSE")
        val asResult = lpm.install("as-IN-standard")
        val asState = lpm.state("as-IN-standard")
        Log.i(tag, "<<< as-IN-standard result=${asResult.javaClass.simpleName} msg=${asResult.self}")
        Log.i(tag, "    as state installed=${asState?.installed} (expected false)")
        assertTrue("Assamese install must REFUSE (downloadable=false)", asResult is LanguagePackOperationResult.Failure)
        assertEquals("Assamese must report not-installed", false, asState?.installed)

        Log.i(tag, "==== END LANG PACK INSTALL — failures=${failures.size} ====")
        if (failures.isNotEmpty()) {
            failures.forEach { Log.e(tag, "FAIL: $it") }
        }
        assertTrue("Baseline pack failures: ${failures.joinToString()}", failures.isEmpty())
    }
}

/** Local shim to expose the sealed result message without a public accessor. */
private val LanguagePackOperationResult.self: String
    get() = when (this) {
        is LanguagePackOperationResult.Success -> message
        is LanguagePackOperationResult.Failure -> message
    }