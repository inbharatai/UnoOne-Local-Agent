package com.unoone.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unoone.agent.core.model.Result
import com.unoone.agent.voice.stt.AndroidSttEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real public fallback entrypoints on a host Android stub; not acoustic/device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalSystemSpeechPolicyTest {
    @Test fun oldAndroidDoesNotUseProviderDependentRecognizer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = AndroidSttEngine(context).initialize()
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message.contains("no cloud fallback"))
    }
    @Test fun directTranscriptionAlsoRejectsBeforeCreatingARecognizer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = AndroidSttEngine(context).transcribeOnce()
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message.contains("offline models required"))
    }
}
