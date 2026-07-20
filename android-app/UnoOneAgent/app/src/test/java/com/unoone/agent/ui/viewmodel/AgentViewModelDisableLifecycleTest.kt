package com.unoone.agent.ui.viewmodel

import android.content.Context
import com.unoone.agent.AgentOrchestrator
import com.unoone.agent.core.model.InputType
import com.unoone.agent.core.model.Result
import com.unoone.agent.core.model.TimelineStep
import com.unoone.agent.core.runtime.AgentRuntimeController
import com.unoone.agent.core.runtime.AgentRuntimeGate
import com.unoone.agent.voice.VoiceModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelDisableLifecycleTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        AgentRuntimeGate.setEnabled(true)
    }

    @After
    fun tearDown() {
        AgentRuntimeGate.setEnabled(true)
        Dispatchers.resetMain()
    }

    @Test
    fun enabledStartupDoesNotRunDisableCleanup() {
        val fixture = fixture(enabled = true)

        assertTrue(fixture.viewModel.isAgentEnabled.value)
        verify(fixture.orchestrator, never()).shutdownForDisable()
        verify(fixture.voice, never()).stopSpeaking()
    }

    @Test
    fun disabledStartupCanEmitImmediatelyDuringConstruction() {
        val fixture = fixture(enabled = false)

        assertFalse(fixture.viewModel.isAgentEnabled.value)
        assertFalse(fixture.viewModel.isListening.value)
        assertFalse(fixture.viewModel.isHandsFree.value)
        verify(fixture.orchestrator, atLeastOnce()).shutdownForDisable()
        verify(fixture.voice, atLeastOnce()).stopRecording()
        verify(fixture.voice, atLeastOnce()).stopSpeaking()
    }

    @Test
    fun enabledToDisabledTransitionClearsTransientState() {
        val fixture = fixture(enabled = true)

        fixture.runtime.disableAgent()

        assertFalse(fixture.viewModel.isAgentEnabled.value)
        verify(fixture.orchestrator, atLeastOnce()).shutdownForDisable()
        verify(fixture.voice, atLeastOnce()).stopSpeaking()
    }

    @Test
    fun repeatedDisableIsIdempotent() {
        val fixture = fixture(enabled = true)

        fixture.viewModel.disableAgent()
        fixture.viewModel.disableAgent()

        assertFalse(fixture.viewModel.isAgentEnabled.value)
        assertFalse(fixture.viewModel.isListening.value)
        assertFalse(fixture.viewModel.isHandsFree.value)
        verify(fixture.orchestrator, atLeast(2)).shutdownForDisable()
    }

    @Test
    fun disableWhileListeningStopsCaptureAndResetsState() = runTest {
        val fixture = fixture(enabled = true)
        whenever(fixture.voice.startRecording(any(), any())).thenReturn(Result.Success(Unit))

        fixture.viewModel.startListening(mock<Context>())
        assertTrue(fixture.viewModel.isListening.value)

        fixture.runtime.disableAgent()

        assertFalse(fixture.viewModel.isListening.value)
        verify(fixture.voice, atLeastOnce()).stopRecording()
    }

    @Test
    fun disableStopsActiveTtsPlayback() {
        val fixture = fixture(enabled = true)

        fixture.runtime.disableAgent()

        verify(fixture.voice, atLeastOnce()).stopSpeaking()
    }

    @Test
    fun disableDuringModelGenerationShutsDownOrchestrator() {
        val fixture = fixture(enabled = true, processing = true)

        fixture.runtime.disableAgent()

        verify(fixture.orchestrator, atLeastOnce()).shutdownForDisable()
    }

    @Test
    fun reEnableDoesNotReplayAnOldCommand() = runTest {
        val fixture = fixture(enabled = false)
        clearInvocations(fixture.orchestrator)

        fixture.viewModel.enableAgent()

        assertTrue(fixture.viewModel.isAgentEnabled.value)
        verify(fixture.orchestrator, never()).processCommand(any(), any<InputType>())
    }

    private fun fixture(enabled: Boolean, processing: Boolean = false): Fixture {
        AgentRuntimeGate.setEnabled(enabled)
        val orchestrator = mock<AgentOrchestrator>()
        whenever(orchestrator.timelineSteps)
            .thenReturn(MutableStateFlow<List<TimelineStep>>(emptyList()))
        whenever(orchestrator.isProcessing).thenReturn(MutableStateFlow(processing))
        whenever(orchestrator.isBlindAidActive).thenReturn(MutableStateFlow(false))

        val voice = mock<VoiceModule>()
        whenever(voice.stopRecording()).thenReturn(ByteArray(0))

        val runtime = FakeRuntimeController(enabled)
        val viewModel = AgentViewModel(orchestrator, voice, runtime)
        return Fixture(viewModel, orchestrator, voice, runtime)
    }

    private data class Fixture(
        val viewModel: AgentViewModel,
        val orchestrator: AgentOrchestrator,
        val voice: VoiceModule,
        val runtime: FakeRuntimeController
    )

    private class FakeRuntimeController(initiallyEnabled: Boolean) : AgentRuntimeController {
        private val enabled = MutableStateFlow(initiallyEnabled)
        override val isAgentEnabled: StateFlow<Boolean> = enabled.asStateFlow()

        override fun disableAgent() {
            AgentRuntimeGate.setEnabled(false)
            enabled.value = false
        }

        override fun enableAgent() {
            AgentRuntimeGate.setEnabled(true)
            enabled.value = true
        }
    }
}
