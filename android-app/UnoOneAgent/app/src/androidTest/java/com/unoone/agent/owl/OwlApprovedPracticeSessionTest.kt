package com.unoone.agent.owl

import android.content.Intent
import com.unoone.agent.R
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unoone.agent.NativeDeviceGoal
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.device.DeviceOutcomeStatus
import com.unoone.agent.core.model.BrainModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real session, native framework fixture only. No fake model and never a real bank/credential UI. */
@RunWith(AndroidJUnit4::class)
class OwlApprovedPracticeSessionTest {
    @Test fun userApprovedSearchHasNativeResultVerification(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("owlPracticeConsent") == "true") {
            "Operator must approve ONE Search click in the non-sensitive practice fixture"
        }
        val app = ApplicationProvider.getApplicationContext<UnoOneApplication>()
        assertTrue(app.brainProviderPreferences.owlOptIn)
        assertEquals(BrainModelRegistry.GUI_OWL_1_5_4B_INSTRUCT, app.orchestrator.loadedBrainProfile())
        val activity = instrumentation.startActivitySync(Intent(app, OwlPracticeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwlPracticeActivity
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val stop = watchdog.schedule({ app.orchestrator.cancelLlmInference("Practice qualification deadline") }, 90, TimeUnit.SECONDS)
        try {
            instrumentation.waitForIdleSync()
            val pkg = app.packageName
            val outcome = withTimeout(100_000) {
                app.orchestrator.runOwlTask(OwlTaskConsent(pkg, "Click Search in the non-sensitive Owl practice screen only", 1, 90),
                    listOf(NativeDeviceGoal.Click(pkg, "Search")))
            }
            assertEquals("Session must verify native postcondition, not model done or dispatch alone: $outcome", DeviceOutcomeStatus.VERIFIED, outcome.status)
            instrumentation.runOnMainSync {
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                fun texts(group: ViewGroup): List<String> = (0 until group.childCount).flatMap { i ->
                    val v = group.getChildAt(i)
                    when (v) { is ViewGroup -> texts(v); is TextView -> listOf(v.text.toString()); else -> emptyList() }
                }
                assertTrue("Native result TextView required", texts(root).contains(activity.getString(R.string.owl_practice_results)))
                assertNull(activity.findViewById<TextView>(R.id.owl_practice_search_button))
            }
        } finally {
            app.orchestrator.cancelLlmInference("Practice test finished")
            stop.cancel(false)
            watchdog.shutdownNow()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
