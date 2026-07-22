package com.unoone.agent.languagepacks

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unoone.agent.MainActivity
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.safety.SecurityLevel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical regression for the application-owned route used by VoiceService after offline STT.
 * It deliberately uses the common "crope" transcript and resolves the real strong confirmation
 * while the first command is still waiting, guarding against serial-flow deadlocks.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCommandToolExecutionDeviceTest {
    private lateinit var app: UnoOneApplication
    private lateinit var context: Context
    private lateinit var originalSecurityLevel: SecurityLevel

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        app = context as UnoOneApplication
        if (!AgentRuntimeGate.isEnabled()) app.enableAgent()
        originalSecurityLevel = SecurityLevel.current(context)
        SecurityLevel.set(context, SecurityLevel.STANDARD)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @After
    fun cleanUp() {
        app.orchestrator.setBlindAidActive(false, announce = false)
        app.orchestrator.onConfirmationRequired = null
        SecurityLevel.set(context, originalSecurityLevel)
    }

    @Test
    fun voiceSttChromeAliasLaunchesChrome() {
        app.postVoiceCommand("open google crope")

        assertTrue("Chrome was not brought to the foreground", await(12_000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.packageName == "com.android.chrome"
        })
    }

    @Test
    fun voiceBlindAidCanBeStrongConfirmedWithoutQueueDeadlock() {
        // Keep the command pending for the actual spoken confirmation instead of a UI tap.
        app.orchestrator.onConfirmationRequired = { _, _ -> }
        app.postVoiceCommand("open blind aid")

        assertTrue("Blind Aid did not reach its confirmation state", await(30_000) {
            app.orchestrator.timelineSteps.value.any { it.label == "Confirmation Required" }
        })
        assertTrue(
            "Spoken confirm did not resolve the pending action",
            await(15_000) {
                app.orchestrator.resolvePendingVoiceConfirmation("confirm")
            }
        )
        assertTrue("Blind Aid did not activate after spoken confirmation", await(15_000) {
            app.orchestrator.isBlindAidActive.value
        })
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}
