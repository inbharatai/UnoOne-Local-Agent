package com.unoone.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

@RunWith(AndroidJUnit4::class)
class MasterDisableInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun restoreEnabled() {
        AgentRuntimeGate.setEnabled(true)
        context.getSharedPreferences(UnoOneApplication.SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(UnoOneApplication.KEY_AGENT_ENABLED, true).commit()
    }

    @Test
    fun disabledPreferenceIsDurableAndNeverSelfEnables() {
        context.getSharedPreferences(UnoOneApplication.SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(UnoOneApplication.KEY_AGENT_ENABLED, false).commit()
        AgentRuntimeGate.setEnabled(false)

        val reread = context.getSharedPreferences(
            UnoOneApplication.SETTINGS_PREFS,
            Context.MODE_PRIVATE
        ).getBoolean(UnoOneApplication.KEY_AGENT_ENABLED, true)
        assertFalse(reread)
        assertFalse(AgentRuntimeGate.isEnabled())
    }

    @Test
    fun microphoneAndTtsEntryPointsFailClosedWhileDisabled() = runBlocking {
        AgentRuntimeGate.setEnabled(false)
        val voice = VoiceModule(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        assertTrue(voice.startRecording(context, scope) is Result.Error)
        assertTrue(voice.speak("must not play") is Result.Error)
        assertTrue(voice.speakAwait("must not play") is Result.Error)
        assertFalse(voice.isRecording())
        voice.release()
    }

    @Test
    fun rapidDisableEnableRaceEndsInLastExplicitState() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(40)
        repeat(40) { index ->
            executor.execute {
                AgentRuntimeGate.setEnabled(index % 2 == 0)
                latch.countDown()
            }
        }
        latch.await()
        AgentRuntimeGate.setEnabled(false)
        executor.shutdownNow()

        assertFalse(AgentRuntimeGate.isEnabled())
        AgentRuntimeGate.setEnabled(true)
        assertTrue(AgentRuntimeGate.isEnabled())
    }

    @Test
    fun applicationTransitionCancelsRuntimeAndDoesNotReplayOldCommand() = runBlocking {
        val app = context as UnoOneApplication
        app.disableAgent()
        delay(300)

        assertFalse(AgentRuntimeGate.isEnabled())
        assertFalse(
            context.getSharedPreferences(UnoOneApplication.SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(UnoOneApplication.KEY_AGENT_ENABLED, true)
        )
        assertFalse(app.sharedVoiceModule.isRecording())
        assertFalse(app.orchestrator.isProcessing.value)
        assertFalse(app.orchestrator.isBlindAidActive.value)

        app.postVoiceCommand("open calendar")
        delay(300)
        assertFalse(app.orchestrator.isProcessing.value)

        app.enableAgent()
        delay(100)
        assertTrue(AgentRuntimeGate.isEnabled())
        assertFalse(app.orchestrator.isProcessing.value)
    }
}
