package com.metatogemini.glasses.presentation

import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.usecase.AudioRoutingUseCase
import com.metatogemini.glasses.domain.usecase.ProcessInterruptionUseCase
import com.metatogemini.glasses.domain.usecase.SendAudioChunkUseCase
import com.metatogemini.glasses.domain.usecase.SendSnapshotUseCase
import com.metatogemini.glasses.domain.usecase.SendVideoFrameUseCase
import com.metatogemini.glasses.domain.usecase.StartLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.StopLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.UpdateSessionConfigUseCase
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import com.metatogemini.glasses.media.camera.CameraFrameData
import com.metatogemini.glasses.media.camera.CameraLens
import com.metatogemini.glasses.media.camera.CameraManager
import com.metatogemini.glasses.mock.GeminiMockEngine
import com.metatogemini.glasses.mock.MicLoopbackManager
import com.metatogemini.glasses.presentation.viewmodel.LiveHudViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Challenger Test Suite for Milestone 5 (Presentation Layer & HUD).
 * Stress tests rapid UI interactions, concurrent streaming + snapshots, rapid mute toggling,
 * mode switches, and coroutine lifecycle / resource cleanup under heavy event floods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerM5RapidInteractionAdversarialTest {

    private val testDispatcher = StandardTestDispatcher()

    private val testDispatchersProvider = object : DispatchersProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val startLiveSessionUseCase: StartLiveSessionUseCase = mockk(relaxed = true)
    private val stopLiveSessionUseCase: StopLiveSessionUseCase = mockk(relaxed = true)
    private val sendAudioChunkUseCase: SendAudioChunkUseCase = mockk(relaxed = true)
    private val sendVideoFrameUseCase: SendVideoFrameUseCase = mockk(relaxed = true)
    private val sendSnapshotUseCase: SendSnapshotUseCase = mockk(relaxed = true)
    private val processInterruptionUseCase: ProcessInterruptionUseCase = mockk(relaxed = true)
    private val audioRoutingUseCase: AudioRoutingUseCase = mockk(relaxed = true)
    private val updateSessionConfigUseCase: UpdateSessionConfigUseCase = mockk(relaxed = true)

    private val liveSessionRepository: LiveSessionRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val audioCaptureManager: AudioCaptureManager = mockk(relaxed = true)
    private val audioPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)
    private val cameraManager: CameraManager = mockk(relaxed = true)
    private val geminiMockEngine: GeminiMockEngine = mockk(relaxed = true)
    private val micLoopbackManager: MicLoopbackManager = mockk(relaxed = true)

    private val sessionConfigFlow = MutableStateFlow(SessionConfig(apiKey = "valid_key", isMockMode = false))
    private val liveConnectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val liveMessagesFlow = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 256)

    private val mockConnectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mockMessagesFlow = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 256)

    private val audioCaptureStream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    private val cameraFrameStream = MutableSharedFlow<CameraFrameData>(extraBufferCapacity = 256)

    private val bluetoothStateFlow = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    private val audioRouteFlow = MutableStateFlow<AudioRoute>(AudioRoute.Speaker)
    private val micRmsFlow = MutableStateFlow(AudioRmsState(rms = 10f, normalizedRms = 0.1f))
    private val speakerRmsFlow = MutableStateFlow(AudioRmsState(rms = 20f, normalizedRms = 0.2f))

    private val cameraLensFlow = MutableStateFlow(CameraLens.BACK)
    private val torchFlow = MutableStateFlow(false)
    private val streamingFramesFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { settingsRepository.sessionConfig } returns sessionConfigFlow
        coEvery { settingsRepository.getSessionConfig() } returns sessionConfigFlow.value

        every { liveSessionRepository.connectionState } returns liveConnectionFlow
        every { liveSessionRepository.incomingMessages } returns liveMessagesFlow.asSharedFlow()

        every { geminiMockEngine.connectionState } returns mockConnectionFlow
        every { geminiMockEngine.incomingMessages } returns mockMessagesFlow.asSharedFlow()

        every { audioRoutingUseCase.bluetoothState } returns bluetoothStateFlow
        every { audioRoutingUseCase.audioRoute } returns audioRouteFlow

        every { audioCaptureManager.rmsState } returns micRmsFlow
        every { audioCaptureManager.startCapture(any()) } returns audioCaptureStream.asSharedFlow()

        every { audioPlaybackManager.playbackRmsState } returns speakerRmsFlow

        every { cameraManager.currentLens } returns cameraLensFlow
        every { cameraManager.isTorchEnabled } returns torchFlow
        every { cameraManager.isStreamingFrames } returns streamingFramesFlow
        every { cameraManager.startFrameStream(any(), any(), any()) } returns cameraFrameStream.asSharedFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LiveHudViewModel {
        return LiveHudViewModel(
            startLiveSessionUseCase = startLiveSessionUseCase,
            stopLiveSessionUseCase = stopLiveSessionUseCase,
            sendAudioChunkUseCase = sendAudioChunkUseCase,
            sendVideoFrameUseCase = sendVideoFrameUseCase,
            sendSnapshotUseCase = sendSnapshotUseCase,
            processInterruptionUseCase = processInterruptionUseCase,
            audioRoutingUseCase = audioRoutingUseCase,
            updateSessionConfigUseCase = updateSessionConfigUseCase,
            liveSessionRepository = liveSessionRepository,
            settingsRepository = settingsRepository,
            audioCaptureManager = audioCaptureManager,
            audioPlaybackManager = audioPlaybackManager,
            cameraManager = cameraManager,
            geminiMockEngine = geminiMockEngine,
            micLoopbackManager = micLoopbackManager,
            dispatchersProvider = testDispatchersProvider
        )
    }

    @Test
    fun adversarial_rapidMuteTogglingWhileStreamingAudioChunks() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected
        vm.startSession()
        advanceUntilIdle()
        val sentAudioChunksCount = AtomicInteger(0)
        coEvery { sendAudioChunkUseCase.invoke(any<ByteArray>()) } answers {
            sentAudioChunksCount.incrementAndGet()
            com.metatogemini.glasses.core.common.Result.Success(Unit)
        }

        // Stress: 100 rapid mute toggles interleaved with audio chunks
        for (i in 1..100) {
            vm.toggleMicMute()
            audioCaptureStream.emit(ByteArray(512) { idx -> ((i + idx) % 128).toByte() })
        }
        advanceUntilIdle()

        // 100 toggles results in unmuted (original false -> 100 toggles -> false)
        assertFalse(vm.uiState.value.isMicMuted)

        // Verify pause and resume calls occurred 50 times each
        verify(exactly = 50) { audioCaptureManager.pauseCapture() }
        verify(exactly = 50) { audioCaptureManager.resumeCapture() }

        // Emit final chunk when unmuted -> must be sent
        val initialCount = sentAudioChunksCount.get()
        audioCaptureStream.emit(ByteArray(512))
        advanceUntilIdle()
        assertTrue(sentAudioChunksCount.get() > initialCount)
    }

    @Test
    fun adversarial_triggerSnapshotConcurrentlyDuringLiveWebSocketStreaming() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected
        vm.startSession()
        advanceUntilIdle()

        // Simulate frame delivery so latestFrameData is populated
        val dummyFrame = CameraFrameData(
            jpegBytes = byteArrayOf(10, 20, 30, 40),
            base64Jpeg = "Cg==AQ==",
            width = 1280,
            height = 720,
            rotationDegrees = 0,
            timestampMs = 12345L
        )
        cameraFrameStream.emit(dummyFrame)
        advanceUntilIdle()

        val mockSnapshotResponse = SnapshotResponse(text = "A red coffee mug on wooden table", latencyMs = 250L)
        coEvery { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) } returns Result.Success(mockSnapshotResponse)

        // Concurrently stream audio and text from WebSocket while triggering snapshot
        vm.captureSnapshot("Identify object")
        assertTrue(vm.uiState.value.isSnapshotAnalyzing)

        // Incoming assistant messages streaming simultaneously
        liveMessagesFlow.emit(GeminiMessage.TextData("Gemini is speaking live...", isFromUser = false))
        liveMessagesFlow.emit(GeminiMessage.AudioData(byteArrayOf(1, 2, 3)))
        advanceUntilIdle()

        // Snapshot finished
        assertFalse(vm.uiState.value.isSnapshotAnalyzing)
        assertEquals("A red coffee mug on wooden table", vm.uiState.value.latestSnapshotResult?.text)
        assertTrue(vm.uiState.value.isSnapshotInspectorOpen)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), vm.uiState.value.latestSnapshotBytes)

        // WebSocket stream transcript remains preserved and unaffected
        assertEquals("Gemini is speaking live...", vm.uiState.value.currentTranscript)
        assertTrue(vm.uiState.value.isAssistantSpeaking)
        verify { audioPlaybackManager.enqueueAudio(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun adversarial_snapshotFailureDegradesGracefullyWithoutDisconnectingWebSocket() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected
        vm.startSession()
        advanceUntilIdle()

        coEvery { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) } returns Result.Error(
            IllegalStateException("HTTP 503 Service Unavailable")
        )

        vm.captureSnapshot("Check vision")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSnapshotAnalyzing)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("HTTP 503"))
        assertFalse(vm.uiState.value.isSnapshotInspectorOpen)

        // Connection state should still remain connected
        assertTrue(vm.uiState.value.isConnected)

        // Clear error banner
        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun adversarial_rapidModeSwitchingBetweenLiveAndMock() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected
        vm.startSession()
        advanceUntilIdle()

        // Rapidly toggle mock mode 20 times while active
        for (i in 1..20) {
            val enableMock = (i % 2 == 1)
            sessionConfigFlow.value = sessionConfigFlow.value.copy(isMockMode = enableMock)
            vm.toggleMockMode(enableMock)
            advanceUntilIdle()
        }

        // Final state is enableMock = false (20 is even)
        assertFalse(vm.uiState.value.isMockMode)
        coVerify(atLeast = 10) { updateSessionConfigUseCase.updateMockMode(true) }
        coVerify(atLeast = 10) { updateSessionConfigUseCase.updateMockMode(false) }
    }

    @Test
    fun adversarial_rapidBargeInInterruptionUnderHeavyTokenFlood() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected
        vm.startSession()
        advanceUntilIdle()

        // Flood text chunks and interruptions in interleaved fashion
        for (i in 1..20) {
            liveMessagesFlow.emit(GeminiMessage.TextData("Token_$i ", isFromUser = false))
            advanceUntilIdle()
            if (i % 4 == 0) {
                vm.triggerBargeIn()
                advanceUntilIdle()
            }
        }

        // Final barge-in on any trailing speech
        vm.triggerBargeIn()
        advanceUntilIdle()

        // Interruptions should have cleared playback & recorded interrupted turns
        verify(atLeast = 5) { processInterruptionUseCase.invoke() }
        assertFalse(vm.uiState.value.isAssistantSpeaking)
        assertEquals("", vm.uiState.value.currentTranscript)
        assertTrue(vm.uiState.value.subtitleHistory.any { it.isInterrupted })
    }

    @Test
    fun adversarial_rapidStartStopSessionCyclesEnsureResourceCleanup() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        liveConnectionFlow.value = ConnectionState.Connected

        // 10 cycles of rapid start/stop
        for (i in 1..10) {
            vm.startSession()
            advanceUntilIdle()
            liveConnectionFlow.value = ConnectionState.Connected
            advanceUntilIdle()
            vm.stopSession()
            advanceUntilIdle()
        }

        // Both startSession (pre-clean) and stopSession call stopCapture & stopAndClear (20 times total)
        verify(exactly = 20) { audioCaptureManager.stopCapture() }
        verify(exactly = 20) { audioPlaybackManager.stopAndClear() }
        coVerify(exactly = 10) { stopLiveSessionUseCase.invoke() }
        assertEquals(ConnectionState.Disconnected, vm.uiState.value.connectionState)
    }

    @Test
    fun adversarial_cameraToggleDuringStreamingAndDisconnection() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()
        liveConnectionFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isCameraEnabled)
        assertTrue(vm.uiState.value.isConnected)

        // Disable camera
        vm.toggleCamera(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isCameraEnabled)
        verify { cameraManager.pauseFrameStream() }

        // Enable camera back while connected
        vm.toggleCamera(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isCameraEnabled)
        verify(atLeast = 2) { cameraManager.startFrameStream(any(), any(), any()) }
    }

    @Test
    fun adversarial_viewModelOnClearedReleasesAllHardwarePipelines() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        // Use reflection to invoke protected onCleared()
        val onClearedMethod = LiveHudViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)

        verify(atLeast = 1) { audioCaptureManager.stopCapture() }
        verify(atLeast = 1) { audioPlaybackManager.stopAndClear() }
        verify(atLeast = 1) { audioRoutingUseCase.stopMonitoring() }
        verify(atLeast = 1) { cameraManager.unbind() }
        verify(atLeast = 1) { audioPlaybackManager.release() }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray?) {
        assertNotNull(actual)
        assertEquals(expected.size, actual!!.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i])
        }
    }
}
