package com.metatogemini.glasses.data.repository

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production implementation of [LiveSessionRepository] managing live full-duplex session
 * via [GeminiLiveWebSocket].
 */
class LiveSessionRepositoryImpl(
    private val liveWebSocket: GeminiLiveWebSocket,
    private val dispatchersProvider: DispatchersProvider
) : LiveSessionRepository {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 128)
    override val incomingMessages: Flow<GeminiMessage> = _incomingMessages.asSharedFlow()

    private var sessionScope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)
    private var eventsCollectorJob: Job? = null

    override suspend fun startSession(config: SessionConfig) = withContext(dispatchersProvider.io) {
        stopSession()
        sessionScope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)

        _connectionState.value = ConnectionState.Connecting
        AppLogger.i(TAG, "Starting live session with model: ${config.model}, voice: ${config.voice}")

        eventsCollectorJob = sessionScope.launch {
            liveWebSocket.events.collect { event ->
                handleSocketEvent(event)
            }
        }

        liveWebSocket.connect(
            apiKey = config.apiKey,
            model = config.model,
            voiceName = config.voice,
            systemInstruction = config.systemInstruction
        )
    }

    private fun handleSocketEvent(event: LiveSocketEvent) {
        when (event) {
            is LiveSocketEvent.Connecting -> {
                _connectionState.value = ConnectionState.Connecting
            }
            is LiveSocketEvent.Connected -> {
                _connectionState.value = ConnectionState.Connecting
            }
            is LiveSocketEvent.SetupAcknowledged -> {
                _connectionState.value = ConnectionState.Connected
                _incomingMessages.tryEmit(GeminiMessage.SetupComplete)
            }
            is LiveSocketEvent.AudioDataReceived -> {
                _incomingMessages.tryEmit(
                    GeminiMessage.AudioData(pcmBytes = event.pcm24kBytes, sampleRate = 24000)
                )
            }
            is LiveSocketEvent.TextReceived -> {
                _incomingMessages.tryEmit(
                    GeminiMessage.TextData(text = event.text, isFromUser = false)
                )
            }
            is LiveSocketEvent.TurnCompleted -> {
                _incomingMessages.tryEmit(
                    GeminiMessage.TurnComplete(isComplete = event.isComplete)
                )
            }
            is LiveSocketEvent.Interrupted -> {
                _incomingMessages.tryEmit(GeminiMessage.Interruption())
            }
            is LiveSocketEvent.Error -> {
                _connectionState.value = ConnectionState.Failed(
                    throwable = event.throwable,
                    reason = event.message
                )
                _incomingMessages.tryEmit(
                    GeminiMessage.Error(throwable = event.throwable, message = event.message)
                )
            }
            is LiveSocketEvent.Disconnected -> {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray, sampleRate: Int) = withContext(dispatchersProvider.io) {
        if (pcmData.isEmpty()) return@withContext
        val base64Data = DtoMappers.encodeBase64(pcmData)
        liveWebSocket.sendAudioChunk(base64Data)
    }

    override suspend fun sendVideoFrame(jpegData: ByteArray) = withContext(dispatchersProvider.io) {
        if (jpegData.isEmpty()) return@withContext
        val base64Data = DtoMappers.encodeBase64(jpegData)
        liveWebSocket.sendVideoFrame(base64Data)
    }

    override suspend fun sendTextMessage(text: String) = withContext(dispatchersProvider.io) {
        if (text.isBlank()) return@withContext
        _incomingMessages.tryEmit(GeminiMessage.TextData(text = text, isFromUser = true))
        liveWebSocket.sendTextMessage(text)
    }

    override suspend fun stopSession() = withContext(dispatchersProvider.io) {
        eventsCollectorJob?.cancel()
        eventsCollectorJob = null
        liveWebSocket.disconnect()
        sessionScope.cancel()
        _connectionState.value = ConnectionState.Disconnected
        AppLogger.i(TAG, "Live session stopped")
    }

    companion object {
        private const val TAG = "LiveSessionRepoImpl"
    }
}
