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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveHudViewModelTest {

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
    private val glassesPhotoSyncManager: com.metatogemini.glasses.media.camera.GlassesPhotoSyncManager = mockk(relaxed = true)

    private val sessionConfigFlow = MutableStateFlow(SessionConfig(apiKey = "test_key", isMockMode = false))
    private val liveConnectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val liveMessagesFlow = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 64)
    private val glassesPhotoFlow = MutableSharedFlow<com.metatogemini.glasses.media.camera.GlassesPhoto>(extraBufferCapacity = 16)

    private val mockConnectionFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mockMessagesFlow = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 64)

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
        every { liveSessionRepository.incomingMessages } returns liveMessagesFlow

        every { geminiMockEngine.connectionState } returns mockConnectionFlow
        every { geminiMockEngine.incomingMessages } returns mockMessagesFlow

        every { glassesPhotoSyncManager.photoEvents } returns glassesPhotoFlow

        every { audioRoutingUseCase.bluetoothState } returns bluetoothStateFlow
        every { audioRoutingUseCase.audioRoute } returns audioRouteFlow

        every { audioCaptureManager.rmsState } returns micRmsFlow
        every { audioCaptureManager.startCapture(any()) } returns flowOf(ByteArray(1024))

        every { audioPlaybackManager.playbackRmsState } returns speakerRmsFlow

        every { cameraManager.currentLens } returns cameraLensFlow
        every { cameraManager.isTorchEnabled } returns torchFlow
        every { cameraManager.isStreamingFrames } returns streamingFramesFlow
        every { cameraManager.startFrameStream(any(), any(), any()) } returns flowOf(
            CameraFrameData(
                jpegBytes = byteArrayOf(1, 2, 3),
                base64Jpeg = "AQID",
                width = 640,
                height = 480,
                rotationDegrees = 0,
                timestampMs = 1000L
            )
        )
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
            glassesPhotoSyncManager = glassesPhotoSyncManager,
            dispatchersProvider = testDispatchersProvider
        )
    }

    @Test
    fun init_observesAllInitialFlows() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("test_key", state.sessionConfig.apiKey)
        assertEquals(BluetoothAudioState.Disconnected, state.bluetoothState)
        assertEquals(AudioRoute.Speaker, state.audioRoute)
        assertEquals(10f, state.micRms.rms, 0.01f)
        assertEquals(20f, state.speakerRms.rms, 0.01f)
        assertEquals(CameraLens.BACK, state.currentLens)
        assertFalse(state.isTorchEnabled)
        verify { audioRoutingUseCase.startMonitoring() }
    }

    @Test
    fun startSession_withoutApiKey_setsErrorMessageWhenNotMock() = runTest(testDispatcher) {
        sessionConfigFlow.value = SessionConfig(apiKey = "", isMockMode = false)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("API Key is missing"))
    }

    @Test
    fun startSession_inMockMode_startsMockEngine() = runTest(testDispatcher) {
        sessionConfigFlow.value = SessionConfig(apiKey = "", isMockMode = true)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        coVerify { geminiMockEngine.startSession(any()) }
    }

    @Test
    fun startSession_inLiveMode_startsSessionAndCapture() = runTest(testDispatcher) {
        sessionConfigFlow.value = SessionConfig(apiKey = "valid_api_key", isMockMode = false)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.startSession()
        advanceUntilIdle()

        coVerify { startLiveSessionUseCase(any()) }
        verify { audioCaptureManager.startCapture(any()) }
        verify { cameraManager.startFrameStream(any(), any(), any()) }
    }

    @Test
    fun incomingMessages_audioData_enqueuesPlaybackAndSetsSpeaking() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()

        val pcmAudio = byteArrayOf(10, 20, 30)
        liveMessagesFlow.emit(GeminiMessage.AudioData(pcmBytes = pcmAudio))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isAssistantSpeaking)
        verify { audioPlaybackManager.enqueueAudio(pcmAudio) }
    }

    @Test
    fun incomingMessages_textData_updatesTranscriptAndHistory() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()

        // User speech
        liveMessagesFlow.emit(GeminiMessage.TextData(text = "Hello glasses", isFromUser = true))
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.subtitleHistory.size)
        assertEquals("Hello glasses", vm.uiState.value.subtitleHistory.first().text)
        assertTrue(vm.uiState.value.subtitleHistory.first().isFromUser)

        // Assistant streaming text
        liveMessagesFlow.emit(GeminiMessage.TextData(text = "Hello ", isFromUser = false))
        liveMessagesFlow.emit(GeminiMessage.TextData(text = "user!", isFromUser = false))
        advanceUntilIdle()

        assertEquals("Hello user!", vm.uiState.value.currentTranscript)
        assertTrue(vm.uiState.value.isAssistantSpeaking)

        // Turn complete
        liveMessagesFlow.emit(GeminiMessage.TurnComplete(isComplete = true))
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.currentTranscript)
        assertFalse(vm.uiState.value.isAssistantSpeaking)
        assertEquals(2, vm.uiState.value.subtitleHistory.size)
        assertEquals("Hello user!", vm.uiState.value.subtitleHistory[1].text)
        assertFalse(vm.uiState.value.subtitleHistory[1].isInterrupted)
    }

    @Test
    fun incomingMessages_interruption_triggersBargeInAndArchivesHistory() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.startSession()
        advanceUntilIdle()

        liveMessagesFlow.emit(GeminiMessage.TextData(text = "I am speaking some long sentence...", isFromUser = false))
        advanceUntilIdle()

        liveMessagesFlow.emit(GeminiMessage.Interruption(timestampMs = 5555L))
        advanceUntilIdle()

        verify { processInterruptionUseCase.invoke() }
        assertFalse(vm.uiState.value.isAssistantSpeaking)
        assertEquals("", vm.uiState.value.currentTranscript)
        assertEquals(5555L, vm.uiState.value.lastInterruptionTimestamp)
        assertEquals(1, vm.uiState.value.subtitleHistory.size)
        assertTrue(vm.uiState.value.subtitleHistory.first().isInterrupted)
    }

    @Test
    fun stopSession_stopsPipelinesAndClearsPlayback() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.stopSession()
        advanceUntilIdle()

        verify { audioCaptureManager.stopCapture() }
        verify { audioPlaybackManager.stopAndClear() }
        coVerify { stopLiveSessionUseCase.invoke() }
        assertEquals(ConnectionState.Disconnected, vm.uiState.value.connectionState)
    }

    @Test
    fun toggleMicMute_pausesAndResumesCapture() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isMicMuted)

        vm.toggleMicMute()
        assertTrue(vm.uiState.value.isMicMuted)
        verify { audioCaptureManager.pauseCapture() }

        vm.toggleMicMute()
        assertFalse(vm.uiState.value.isMicMuted)
        verify { audioCaptureManager.resumeCapture() }
    }

    @Test
    fun triggerBargeIn_callsInterruptionAndClearsTranscript() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.triggerBargeIn()
        advanceUntilIdle()

        verify { processInterruptionUseCase.invoke() }
        assertFalse(vm.uiState.value.isAssistantSpeaking)
    }

    @Test
    fun captureSnapshot_successfulAnalysis_updatesResultAndOpensInspector() = runTest(testDispatcher) {
        val mockResponse = SnapshotResponse(text = "Desk scene analysis", latencyMs = 200L)
        coEvery { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) } returns Result.Success(mockResponse)

        val vm = createViewModel()
        advanceUntilIdle()

        vm.captureSnapshot("What is on the table?")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSnapshotAnalyzing)
        assertEquals(mockResponse, vm.uiState.value.latestSnapshotResult)
        assertTrue(vm.uiState.value.isSnapshotInspectorOpen)
    }

    @Test
    fun captureSnapshot_error_updatesErrorMessage() = runTest(testDispatcher) {
        coEvery { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) } returns Result.Error(
            IllegalStateException("Network timeout")
        )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.captureSnapshot("Analyze this")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSnapshotAnalyzing)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("Network timeout"))
    }

    @Test
    fun settingsActions_invokeExpectedUseCases() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateApiKey("new_api_key")
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateApiKey("new_api_key") }

        vm.updateModel("gemini-1.5-pro")
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateModel("gemini-1.5-pro") }

        vm.updateVoice("Kore")
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateVoice("Kore") }

        vm.updateSystemInstruction("New instruction")
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateSystemInstruction("New instruction") }

        vm.updateCameraFps(2)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateCameraFps(2) }

        vm.toggleMockMode(true)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateMockMode(true) }

        vm.toggleLoopbackMode(true)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateLoopbackMode(true) }
        coVerify { micLoopbackManager.startLoopback() }

        vm.toggleLoopbackMode(false)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateLoopbackMode(false) }
        coVerify { micLoopbackManager.stopLoopback() }
    }

    @Test
    fun uiModalsAndBanners_stateTogglesCorrectly() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.openSettings(true)
        assertTrue(vm.uiState.value.isSettingsOpen)
        vm.openSettings(false)
        assertFalse(vm.uiState.value.isSettingsOpen)

        vm.openSnapshotInspector(true)
        assertTrue(vm.uiState.value.isSnapshotInspectorOpen)
        vm.openSnapshotInspector(false)
        assertFalse(vm.uiState.value.isSnapshotInspectorOpen)

        vm.reconnectBluetooth()
        verify { audioRoutingUseCase.reconnect() }

        vm.toggleTorch(true)
        verify { cameraManager.toggleTorch(true) }
    }

    @Test
    fun glassesPhotoSync_whenLiveSessionNotRunning_usesRestModal() = runTest(testDispatcher) {
        coEvery {
            sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any())
        } returns Result.Success(
            SnapshotResponse(text = "A photo of a mountain", latencyMs = 250L)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        verify { glassesPhotoSyncManager.startMonitoring() }

        val testPhoto = com.metatogemini.glasses.media.camera.GlassesPhoto(
            id = 200L,
            uri = mockk(relaxed = true),
            jpegBytes = byteArrayOf(9, 8, 7),
            fileName = "meta_photo_200.jpg",
            timestampMs = 12345L,
            source = "Meta View",
            width = 1080,
            height = 1080
        )

        glassesPhotoFlow.emit(testPhoto)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("A photo of a mountain", state.latestSnapshotResult?.text)
        assertTrue(state.isSnapshotInspectorOpen)
        coVerify { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) }
    }

    @Test
    fun glassesPhotoSync_whenLiveSessionActive_streamsDirectlyToLiveWebSocketWithoutModal() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Connect live session
        vm.startSession()
        liveConnectionFlow.value = ConnectionState.Connected
        advanceUntilIdle()

        val testPhoto = com.metatogemini.glasses.media.camera.GlassesPhoto(
            id = 201L,
            uri = mockk(relaxed = true),
            jpegBytes = byteArrayOf(11, 22, 33),
            fileName = "live_meta_photo.jpg",
            timestampMs = 12345L,
            source = "Meta View",
            width = 1080,
            height = 1080
        )

        glassesPhotoFlow.emit(testPhoto)
        advanceUntilIdle()

        val state = vm.uiState.value
        // Modal inspector should NOT open
        assertFalse(state.isSnapshotInspectorOpen)
        assertArrayEquals(byteArrayOf(11, 22, 33), state.latestSnapshotBytes)
        // Video frame streamed to live session use case
        coVerify { sendVideoFrameUseCase.invoke(byteArrayOf(11, 22, 33)) }
        // REST snapshot use case should NOT be called
        coVerify(exactly = 0) { sendSnapshotUseCase.invoke(any<SnapshotRequest>(), any()) }
    }

    @Test
    fun toggleAutoAnalyzeGlassesPhotos_updatesUseCase() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleAutoAnalyzeGlassesPhotos(false)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateAutoAnalyzeGlassesPhotos(false) }

        vm.toggleAutoAnalyzeGlassesPhotos(true)
        advanceUntilIdle()
        coVerify { updateSessionConfigUseCase.updateAutoAnalyzeGlassesPhotos(true) }
    }
}
