package com.metatogemini.glasses.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.model.StreamMetrics
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
import com.metatogemini.glasses.media.camera.CameraFrameData
import com.metatogemini.glasses.media.camera.CameraManager
import com.metatogemini.glasses.media.camera.GlassesPhoto
import com.metatogemini.glasses.media.camera.GlassesPhotoSyncManager
import com.metatogemini.glasses.mock.GeminiMockEngine
import com.metatogemini.glasses.mock.MicLoopbackManager
import com.metatogemini.glasses.presentation.model.HudUiState
import com.metatogemini.glasses.presentation.model.SubtitleEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary ViewModel orchestrating the smart glasses live heads-up display.
 * Integrates live streaming, offline mock sandbox, CameraX analysis, Audio capture/playback,
 * Bluetooth routing, low-latency barge-in interruption, and DataStore settings.
 */
class LiveHudViewModel(
    private val startLiveSessionUseCase: StartLiveSessionUseCase,
    private val stopLiveSessionUseCase: StopLiveSessionUseCase,
    private val sendAudioChunkUseCase: SendAudioChunkUseCase,
    private val sendVideoFrameUseCase: SendVideoFrameUseCase,
    private val sendSnapshotUseCase: SendSnapshotUseCase,
    private val processInterruptionUseCase: ProcessInterruptionUseCase,
    private val audioRoutingUseCase: AudioRoutingUseCase,
    private val updateSessionConfigUseCase: UpdateSessionConfigUseCase,
    private val liveSessionRepository: LiveSessionRepository,
    private val settingsRepository: SettingsRepository,
    private val audioCaptureManager: AudioCaptureManager,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val cameraManager: CameraManager,
    private val geminiMockEngine: GeminiMockEngine,
    private val micLoopbackManager: MicLoopbackManager,
    private val glassesPhotoSyncManager: GlassesPhotoSyncManager? = null,
    private val dispatchersProvider: DispatchersProvider = DefaultDispatchersProvider()
) : ViewModel() {

    companion object {
        private const val TAG = "LiveHudViewModel"
    }

    private val _uiState = MutableStateFlow(HudUiState())
    val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

    private var messageCollectorJob: Job? = null
    private var connectionCollectorJob: Job? = null
    private var audioCaptureJob: Job? = null
    private var cameraStreamJob: Job? = null
    private var latestFrameData: CameraFrameData? = null
    private var glassesPhotoJob: Job? = null

    init {
        observeSettings()
        observeAudioAndBluetooth()
        observeCameraState()
        startGlassesPhotoObservation()
    }

    private fun startGlassesPhotoObservation() {
        glassesPhotoSyncManager?.startMonitoring()
        glassesPhotoJob?.cancel()
        glassesPhotoJob = viewModelScope.launch(dispatchersProvider.main) {
            glassesPhotoSyncManager?.photoEvents?.collect { photo ->
                handleGlassesPhotoCaptured(photo)
            }
        }
    }

    private fun handleGlassesPhotoCaptured(photo: GlassesPhoto) {
        val config = _uiState.value.sessionConfig
        val isLiveSessionActive = _uiState.value.isConnected

        _uiState.update {
            it.copy(latestSnapshotBytes = photo.jpegBytes)
        }

        if (isLiveSessionActive) {
            // Live session is running: pipe photo directly into Gemini Live WebSocket as multimodal visual context
            viewModelScope.launch(dispatchersProvider.io) {
                if (config.isMockMode) {
                    geminiMockEngine.sendVideoFrame(photo.jpegBytes)
                } else {
                    sendVideoFrameUseCase(photo.jpegBytes)
                }
            }
            setUserNotice("📸 Glasses photo sent to live conversation context")
        } else if (config.autoAnalyzeGlassesPhotos) {
            // Live session is NOT running: perform standalone REST snapshot analysis and open inspector modal
            setUserNotice("📸 Photo synced from ${photo.source} (${photo.fileName})")
            analyzeSnapshotPhoto(
                imageBytes = photo.jpegBytes,
                customPrompt = "Describe what you see in this photo taken from the smart glasses in detail and provide actionable advice for the glasses wearer.",
                successNotice = "Smart glasses photo analyzed successfully"
            )
        }
    }

    private fun observeSettings() {
        viewModelScope.launch(dispatchersProvider.main) {
            settingsRepository.sessionConfig.collectLatest { config ->
                _uiState.update { it.copy(sessionConfig = config) }
            }
        }
    }

    private fun observeAudioAndBluetooth() {
        audioRoutingUseCase.startMonitoring()

        viewModelScope.launch(dispatchersProvider.main) {
            audioRoutingUseCase.bluetoothState.collectLatest { btState ->
                _uiState.update { it.copy(bluetoothState = btState) }
            }
        }

        viewModelScope.launch(dispatchersProvider.main) {
            audioRoutingUseCase.audioRoute.collectLatest { route ->
                _uiState.update { it.copy(audioRoute = route) }
            }
        }

        viewModelScope.launch(dispatchersProvider.main) {
            audioCaptureManager.rmsState.collectLatest { rms ->
                _uiState.update { state ->
                    state.copy(
                        micRms = rms,
                        metrics = state.metrics.copy(micRms = rms.normalizedRms)
                    )
                }
            }
        }

        viewModelScope.launch(dispatchersProvider.main) {
            audioPlaybackManager.playbackRmsState.collectLatest { rms ->
                _uiState.update { state ->
                    state.copy(
                        speakerRms = rms,
                        metrics = state.metrics.copy(speakerRms = rms.normalizedRms)
                    )
                }
            }
        }
    }

    private fun observeCameraState() {
        viewModelScope.launch(dispatchersProvider.main) {
            cameraManager.currentLens.collectLatest { lens ->
                _uiState.update { it.copy(currentLens = lens) }
            }
        }
        viewModelScope.launch(dispatchersProvider.main) {
            cameraManager.isTorchEnabled.collectLatest { torch ->
                _uiState.update { it.copy(isTorchEnabled = torch) }
            }
        }
        viewModelScope.launch(dispatchersProvider.main) {
            cameraManager.isStreamingFrames.collectLatest { streaming ->
                _uiState.update { it.copy(isStreamingFrames = streaming) }
            }
        }
    }

    /**
     * Initiates a live bidirectional streaming session with Gemini Live API (or Mock Sandbox).
     */
    fun startSession() {
        val currentConfig = _uiState.value.sessionConfig
        if (!currentConfig.isMockMode && currentConfig.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Gemini API Key is missing. Enter your API key in Settings or enable Developer Mock Mode."
                )
            }
            return
        }

        stopActivePipelines()
        _uiState.update {
            it.copy(
                errorMessage = null,
                connectionState = ConnectionState.Connecting
            )
        }
        setUserNotice("Connecting to Gemini Live...")

        viewModelScope.launch(dispatchersProvider.io) {
            try {
                if (currentConfig.isMockMode) {
                    observeMockConnectionAndMessages()
                    geminiMockEngine.startSession(currentConfig)
                } else {
                    observeLiveConnectionAndMessages()
                    startLiveSessionUseCase(currentConfig)
                }

                startAudioCapturePipeline()
                if (_uiState.value.isCameraEnabled) {
                    startCameraStreamingPipeline(currentConfig.cameraFps)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start live session", e)
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.Failed(e, e.message),
                        errorMessage = "Session start failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun observeLiveConnectionAndMessages() {
        connectionCollectorJob?.cancel()
        connectionCollectorJob = viewModelScope.launch(dispatchersProvider.main) {
            liveSessionRepository.connectionState.collectLatest { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        errorMessage = if (state is ConnectionState.Failed) {
                            state.reason ?: state.throwable?.message ?: "Connection failed"
                        } else it.errorMessage
                    )
                }
                if (state is ConnectionState.Connected) {
                    setUserNotice("Connected to Gemini Live")
                }
            }
        }

        messageCollectorJob?.cancel()
        messageCollectorJob = viewModelScope.launch(dispatchersProvider.main) {
            liveSessionRepository.incomingMessages.collect { message ->
                handleIncomingGeminiMessage(message)
            }
        }
    }

    private fun observeMockConnectionAndMessages() {
        connectionCollectorJob?.cancel()
        connectionCollectorJob = viewModelScope.launch(dispatchersProvider.main) {
            geminiMockEngine.connectionState.collectLatest { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        messageCollectorJob?.cancel()
        messageCollectorJob = viewModelScope.launch(dispatchersProvider.main) {
            geminiMockEngine.incomingMessages.collect { message ->
                handleIncomingGeminiMessage(message)
            }
        }
    }

    private fun handleIncomingGeminiMessage(message: GeminiMessage) {
        when (message) {
            is GeminiMessage.SetupComplete -> {
                _uiState.update {
                    it.copy(
                        userNotice = "Live Session Connected",
                        isAssistantSpeaking = false
                    )
                }
            }
            is GeminiMessage.AudioData -> {
                _uiState.update { it.copy(isAssistantSpeaking = true) }
                audioPlaybackManager.enqueueAudio(message.pcmBytes)
            }
            is GeminiMessage.TextData -> {
                if (message.isFromUser) {
                    val entry = SubtitleEntry(text = message.text, isFromUser = true)
                    _uiState.update {
                        it.copy(subtitleHistory = it.subtitleHistory + entry)
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(
                            isAssistantSpeaking = true,
                            currentTranscript = state.currentTranscript + message.text
                        )
                    }
                }
            }
            is GeminiMessage.Interruption -> {
                processInterruptionUseCase()
                val currentText = _uiState.value.currentTranscript
                _uiState.update { state ->
                    val updatedHistory = if (currentText.isNotBlank()) {
                        state.subtitleHistory + SubtitleEntry(
                            text = currentText,
                            isFromUser = false,
                            isInterrupted = true
                        )
                    } else {
                        state.subtitleHistory
                    }
                    state.copy(
                        currentTranscript = "",
                        isAssistantSpeaking = false,
                        lastInterruptionTimestamp = message.timestampMs,
                        subtitleHistory = updatedHistory,
                        userNotice = "Interrupted by user"
                    )
                }
            }
            is GeminiMessage.TurnComplete -> {
                val currentText = _uiState.value.currentTranscript
                _uiState.update { state ->
                    val updatedHistory = if (currentText.isNotBlank()) {
                        state.subtitleHistory + SubtitleEntry(
                            text = currentText,
                            isFromUser = false,
                            isInterrupted = false
                        )
                    } else {
                        state.subtitleHistory
                    }
                    state.copy(
                        currentTranscript = "",
                        isAssistantSpeaking = false,
                        subtitleHistory = updatedHistory
                    )
                }
            }
            is GeminiMessage.Error -> {
                _uiState.update {
                    it.copy(errorMessage = message.message ?: "Gemini Stream Error")
                }
            }
        }
    }

    private fun startAudioCapturePipeline() {
        audioCaptureJob?.cancel()
        audioCaptureJob = viewModelScope.launch(dispatchersProvider.io) {
            audioCaptureManager.startCapture(Constants.CAPTURE_CHUNK_SAMPLES)
                .catch { e ->
                    AppLogger.e(TAG, "Audio capture error", e)
                }
                .collect { pcmChunk ->
                    val currentState = _uiState.value
                    if (!currentState.isMicMuted && (currentState.isConnected || currentState.connectionState is ConnectionState.Connected)) {
                        if (currentState.isMockMode) {
                            geminiMockEngine.sendAudioChunk(pcmChunk)
                        } else {
                            sendAudioChunkUseCase(pcmChunk)
                        }
                    }
                }
        }
    }

    private fun startCameraStreamingPipeline(fps: Int) {
        cameraStreamJob?.cancel()
        cameraStreamJob = viewModelScope.launch(dispatchersProvider.io) {
            cameraManager.startFrameStream(fps = fps)
                .catch { e ->
                    AppLogger.e(TAG, "Camera streaming error", e)
                }
                .collect { frameData ->
                    latestFrameData = frameData
                    if (_uiState.value.isCameraEnabled && _uiState.value.isConnected) {
                        val isMock = _uiState.value.isMockMode
                        if (isMock) {
                            geminiMockEngine.sendVideoFrame(frameData.jpegBytes)
                        } else {
                            sendVideoFrameUseCase(frameData.jpegBytes)
                        }
                    }
                }
        }
    }

    /**
     * Terminates the active live session, stops hardware capture/playback, and flushes queues.
     */
    fun stopSession() {
        viewModelScope.launch(dispatchersProvider.io) {
            stopActivePipelines()
            if (_uiState.value.isMockMode) {
                geminiMockEngine.stopSession()
            } else {
                stopLiveSessionUseCase()
            }
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.Disconnected,
                    isAssistantSpeaking = false,
                    userNotice = "Live Session Stopped"
                )
            }
        }
    }

    private fun stopActivePipelines() {
        audioCaptureJob?.cancel()
        cameraStreamJob?.cancel()
        messageCollectorJob?.cancel()
        connectionCollectorJob?.cancel()
        audioCaptureManager.stopCapture()
        audioPlaybackManager.stopAndClear()
    }

    /**
     * Toggles microphone mute state.
     */
    fun toggleMicMute() {
        val newMuted = !_uiState.value.isMicMuted
        _uiState.update { it.copy(isMicMuted = newMuted) }
        if (newMuted) {
            audioCaptureManager.pauseCapture()
        } else {
            audioCaptureManager.resumeCapture()
        }
    }

    /**
     * Toggles camera enabled state.
     */
    fun toggleCamera(enabled: Boolean? = null) {
        val newEnabled = enabled ?: !_uiState.value.isCameraEnabled
        _uiState.update { it.copy(isCameraEnabled = newEnabled) }
        if (newEnabled && _uiState.value.isConnected) {
            startCameraStreamingPipeline(_uiState.value.sessionConfig.cameraFps)
        } else if (!newEnabled) {
            cameraStreamJob?.cancel()
            cameraManager.pauseFrameStream()
        }
    }

    /**
     * Triggers instantaneous low-latency barge-in interruption (<10ms).
     */
    fun triggerBargeIn() {
        processInterruptionUseCase()
        if (_uiState.value.isMockMode) {
            geminiMockEngine.triggerInterruption()
        }
        val currentText = _uiState.value.currentTranscript
        _uiState.update { state ->
            val updatedHistory = if (currentText.isNotBlank()) {
                state.subtitleHistory + SubtitleEntry(
                    text = currentText,
                    isFromUser = false,
                    isInterrupted = true
                )
            } else {
                state.subtitleHistory
            }
            state.copy(
                currentTranscript = "",
                isAssistantSpeaking = false,
                lastInterruptionTimestamp = System.currentTimeMillis(),
                subtitleHistory = updatedHistory,
                userNotice = "Speech Interrupted"
            )
        }
    }

    /**
     * Captures a high-resolution snapshot from CameraX and queries Gemini Multimodal REST API.
     */
    fun captureSnapshot(
        customPrompt: String = "Describe what you see in detail and provide actionable advice for the glasses wearer."
    ) {
        val frame = latestFrameData
        val imageBytes = frame?.jpegBytes ?: ByteArray(0)
        analyzeSnapshotPhoto(
            imageBytes = imageBytes,
            customPrompt = customPrompt,
            successNotice = "Snapshot analyzed successfully"
        )
    }

    /**
     * Analyzes an image with Gemini REST API (or Mock Engine) and updates the snapshot inspector.
     */
    fun analyzeSnapshotPhoto(
        imageBytes: ByteArray,
        customPrompt: String,
        successNotice: String = "Photo analyzed successfully"
    ) {
        _uiState.update {
            it.copy(
                isSnapshotAnalyzing = true,
                latestSnapshotBytes = imageBytes,
                errorMessage = null
            )
        }

        viewModelScope.launch(dispatchersProvider.io) {
            val config = _uiState.value.sessionConfig
            val result: Result<SnapshotResponse> = if (config.isMockMode) {
                geminiMockEngine.analyzeSnapshot(
                    SnapshotRequest(imageBytes = imageBytes, prompt = customPrompt),
                    config
                )
            } else {
                sendSnapshotUseCase(
                    SnapshotRequest(imageBytes = imageBytes, prompt = customPrompt),
                    config
                )
            }

            withContext(dispatchersProvider.main) {
                when (result) {
                    is Result.Success -> {
                        _uiState.update {
                            it.copy(
                                isSnapshotAnalyzing = false,
                                latestSnapshotResult = result.data,
                                isSnapshotInspectorOpen = true,
                                userNotice = successNotice
                            )
                        }
                    }
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(
                                isSnapshotAnalyzing = false,
                                errorMessage = "Snapshot analysis failed: ${result.message ?: result.exception.message}"
                            )
                        }
                    }
                    Result.Loading -> {
                        _uiState.update { it.copy(isSnapshotAnalyzing = true) }
                    }
                }
            }
        }
    }

    /**
     * Toggles Developer Mock Engine mode.
     */
    fun toggleMockMode(enabled: Boolean) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateMockMode(enabled)
            if (_uiState.value.isConnected) {
                startSession() // Reconnect under new mode
            }
        }
    }

    /**
     * Toggles hardware microphone loopback mode for zero-latency testing.
     */
    fun toggleLoopbackMode(enabled: Boolean) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateLoopbackMode(enabled)
            if (enabled) {
                micLoopbackManager.startLoopback()
            } else {
                micLoopbackManager.stopLoopback()
            }
        }
    }

    /**
     * Updates persistent configuration settings.
     */
    fun updateApiKey(apiKey: String) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateApiKey(apiKey.trim())
        }
    }

    fun updateModel(model: String) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateModel(model)
        }
    }

    fun updateVoice(voice: String) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateVoice(voice)
        }
    }

    fun updateSystemInstruction(instruction: String) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateSystemInstruction(instruction)
        }
    }

    fun updateCameraFps(fps: Int) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateCameraFps(fps)
        }
    }

    fun toggleAutoAnalyzeGlassesPhotos(enabled: Boolean) {
        viewModelScope.launch(dispatchersProvider.io) {
            updateSessionConfigUseCase.updateAutoAnalyzeGlassesPhotos(enabled)
        }
    }

    fun reconnectBluetooth() {
        audioRoutingUseCase.reconnect()
    }

    fun toggleTorch(enable: Boolean) {
        cameraManager.toggleTorch(enable)
    }

    fun openSettings(open: Boolean) {
        _uiState.update { it.copy(isSettingsOpen = open) }
    }

    fun openSnapshotInspector(open: Boolean) {
        _uiState.update { it.copy(isSnapshotInspectorOpen = open) }
    }

    fun setUserNotice(notice: String?) {
        _uiState.update { it.copy(userNotice = notice) }
        if (notice != null) {
            viewModelScope.launch(dispatchersProvider.main) {
                delay(3500)
                _uiState.update { if (it.userNotice == notice) it.copy(userNotice = null) else it }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearUserNotice() {
        _uiState.update { it.copy(userNotice = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopActivePipelines()
        glassesPhotoSyncManager?.stopMonitoring()
        audioRoutingUseCase.stopMonitoring()
        cameraManager.unbind()
        audioPlaybackManager.release()
    }
}
