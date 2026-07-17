package com.unoone.agent.languagepacks

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unoone.agent.UnoOneApplication
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.voice.VoiceService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device gate for the final in-process hop used by the background wake/STT loop. A real transcript
 * must enter VoiceService and reach the same callback used by UnoOneApplication without a broadcast,
 * exported component or network service.
 */
@RunWith(AndroidJUnit4::class)
class HandsFreeVoiceActivationTest {

    private lateinit var app: UnoOneApplication

    @Before
    fun enableAgent() {
        app = ApplicationProvider.getApplicationContext()
        if (!AgentRuntimeGate.isEnabled()) app.enableAgent()
    }

    @After
    fun restoreProductionCallback() {
        VoiceService.voiceCommandCallback = { command -> app.postVoiceCommand(command) }
    }

    @Test
    fun injectedOfflineTranscriptReachesThePrivateVoiceCommandRoute() {
        val expected = "स्क्रीन पढ़ो"
        val latch = CountDownLatch(1)
        var received: String? = null
        VoiceService.voiceCommandCallback = { command ->
            received = command
            latch.countDown()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, VoiceService::class.java)
            .setAction(VoiceService.ACTION_VOICE_COMMAND)
            .putExtra(VoiceService.EXTRA_COMMAND, expected)
        ContextCompat.startForegroundService(context, intent)

        assertTrue("VoiceService did not route the transcript within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        assertEquals(expected, received)
    }
}
