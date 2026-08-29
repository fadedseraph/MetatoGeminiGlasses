package com.metatogemini.glasses.data.network.websocket

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.GeminiClientContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveBlob
import com.metatogemini.glasses.data.network.dto.GeminiLiveClientMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveGenerationConfig
import com.metatogemini.glasses.data.network.dto.GeminiLivePart
import com.metatogemini.glasses.data.network.dto.GeminiLivePrebuiltVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveSetup
import com.metatogemini.glasses.data.network.dto.GeminiLiveSpeechConfig
import com.metatogemini.glasses.data.network.dto.GeminiLiveVoiceConfig
import com.metatogemini.glasses.data.network.dto.GeminiRealtimeInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Socket lifecycle events for Gemini Multimodal Live API.
 */
sealed interface LiveSocketEvent {
    data object Connecting : LiveSocketEvent
    data object Connected : LiveSocketEvent
    data object SetupAcknowledged : LiveSocketEvent
    data class AudioDataReceived(val pcm24kBytes: ByteArray) : LiveSocketEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioDataReceived
            return pcm24kBytes.contentEquals(other.pcm24kBytes)
        }

        override fun hashCode(): Int = pcm24kBytes.contentHashCode()
    }
    data class TextReceived(val text: String) : LiveSocketEvent
    data class TurnCompleted(val isComplete: Boolean) : LiveSocketEvent
    data object Interrupted : LiveSocketEvent
    data class Error(val throwable: Throwable, val message: String? = throwable.message) : LiveSocketEvent
    data object Disconnected : LiveSocketEvent
}

/**
 * Gemini Multimodal Live WebSocket client handling bidirectional full-duplex streaming.
 */
class GeminiLiveWebSocket(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val dispatchersProvider: DispatchersProvider
) {

    private val _events = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<LiveSocketEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val _isConnected = AtomicBoolean(false)
    val isConnected: Boolean get() = _isConnected.get()

    private var scope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)

    /**
     * Connects to the Gemini Live Bidi WebSocket endpoint and initiates the setup handshake.
     */
    @Synchronized
    fun connect(
        apiKey: String,
        model: String = Constants.DEFAULT_MODEL,
        voiceName: String = Constants.DEFAULT_VOICE,
        systemInstruction: String = Constants.DEFAULT_SYSTEM_INSTRUCTION
    ) {
        if (apiKey.isBlank()) {
            _events.tryEmit(LiveSocketEvent.Error(IllegalArgumentException("Gemini API key cannot be blank")))
            return
        }

        disconnect()
        scope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)

        val modelIdentifier = if (model.startsWith("models/")) model.removePrefix("models/") else model
        val baseUrl = Constants.GEMINI_LIVE_WS_BASE_URL
        val requestBuilder = Request.Builder()
            .url("$baseUrl?key=$apiKey")
            .addHeader("x-goog-api-key", apiKey)

        if (apiKey.startsWith("ya29.")) {
            // Google Cloud OAuth 2.0 Access Token
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        val streamingClient = okHttpClient.newBuilder()
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        _events.tryEmit(LiveSocketEvent.Connecting)
        AppLogger.i(TAG, "Connecting to Gemini Live WebSocket: $model")

        webSocket = streamingClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.set(true)
                AppLogger.i(TAG, "Gemini Live WebSocket opened successfully")
                _events.tryEmit(LiveSocketEvent.Connected)
                sendSetup(modelIdentifier, voiceName, systemInstruction)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLogger.d(TAG, "WebSocket text message: $text")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                AppLogger.d(TAG, "WebSocket binary message: ${bytes.size} bytes")
                _events.tryEmit(LiveSocketEvent.AudioDataReceived(bytes.toByteArray()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.set(false)
                val responseBody = try { response?.body?.string() } catch (e: Exception) { null }
                val errorDetails = listOfNotNull(
                    t.message,
                    response?.message?.takeIf { it.isNotBlank() },
                    responseBody?.takeIf { it.isNotBlank() }
                ).joinToString(" | ")
                val finalMsg = if (errorDetails.isNotBlank()) errorDetails else "WebSocket connection failure"
                AppLogger.e(TAG, "Gemini Live WebSocket failure: $finalMsg", t)
                _events.tryEmit(LiveSocketEvent.Error(IOException(finalMsg, t), finalMsg))
                _events.tryEmit(LiveSocketEvent.Disconnected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.set(false)
                AppLogger.i(TAG, "Gemini Live WebSocket closed code=$code reason=$reason")
                if (code != 1000) {
                    val errorMsg = "Server closed connection (code=$code, reason=$reason)"
                    _events.tryEmit(LiveSocketEvent.Error(IOException(errorMsg), errorMsg))
                }
                _events.tryEmit(LiveSocketEvent.Disconnected)
            }
        })
    }

    /**
     * Sends the setup handshake message to configure model, voice, and system instructions.
     */
    fun sendSetup(
        model: String,
        voiceName: String,
        systemInstruction: String
    ) {
        val modelTarget = if (model.startsWith("models/")) model else "models/$model"
        val setupPayload = GeminiLiveClientMessage(
            setup = GeminiLiveSetup(
                model = modelTarget,
                generationConfig = GeminiLiveGenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = GeminiLiveSpeechConfig(
                        voiceConfig = GeminiLiveVoiceConfig(
                            prebuiltVoiceConfig = GeminiLivePrebuiltVoiceConfig(voiceName = voiceName)
                        )
                    )
                ),
                systemInstruction = if (systemInstruction.isNotBlank()) {
                    GeminiLiveContent(
                        role = "system",
                        parts = listOf(GeminiLivePart(text = systemInstruction))
                    )
                } else null
            )
        )

        try {
            val jsonString = json.encodeToString(setupPayload)
            webSocket?.send(jsonString)
            AppLogger.d(TAG, "Setup message sent for model: $modelTarget")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to send setup message", e)
            _events.tryEmit(LiveSocketEvent.Error(e))
        }
    }

    private var sentAudioChunksCount = 0L

    /**
     * Streams 16kHz PCM audio chunk in Base64 encoding.
     */
    fun sendAudioChunk(pcm16kBase64: String) {
        if (!_isConnected.get() || pcm16kBase64.isBlank()) return
        val chunkMessage = GeminiLiveClientMessage(
            realtimeInput = GeminiRealtimeInput(
                mediaChunks = listOf(
                    GeminiLiveBlob(
                        mimeType = Constants.MIME_TYPE_PCM,
                        data = pcm16kBase64
                    )
                )
            )
        )
        try {
            val payload = json.encodeToString(chunkMessage)
            val sent = webSocket?.send(payload) ?: false
            if (sent) {
                sentAudioChunksCount++
                if (sentAudioChunksCount % 50L == 0L) {
                    AppLogger.d(TAG, "Sent $sentAudioChunksCount audio chunks to Gemini Live WebSocket")
                }
            } else {
                AppLogger.w(TAG, "webSocket.send returned false for audio chunk")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error sending audio chunk", e)
        }
    }

    /**
     * Streams JPEG video frame in Base64 encoding.
     */
    fun sendVideoFrame(jpegBase64: String) {
        if (!_isConnected.get() || jpegBase64.isBlank()) return
        val frameMessage = GeminiLiveClientMessage(
            realtimeInput = GeminiRealtimeInput(
                mediaChunks = listOf(
                    GeminiLiveBlob(
                        mimeType = Constants.MIME_TYPE_JPEG,
                        data = jpegBase64
                    )
                )
            )
        )
        try {
            val payload = json.encodeToString(frameMessage)
            webSocket?.send(payload)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error sending video frame", e)
        }
    }

    /**
     * Sends explicit client text prompt injection.
     */
    fun sendTextMessage(text: String) {
        if (!_isConnected.get() || text.isBlank()) return
        val textMessage = GeminiLiveClientMessage(
            clientContent = GeminiClientContent(
                turns = listOf(
                    GeminiLiveContent(
                        role = "user",
                        parts = listOf(GeminiLivePart(text = text))
                    )
                ),
                turnComplete = true
            )
        )
        try {
            val payload = json.encodeToString(textMessage)
            webSocket?.send(payload)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error sending text message", e)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        scope.launch {
            try {
                val serverMsg = json.decodeFromString<GeminiLiveServerMessage>(jsonText)

                if (serverMsg.error != null) {
                    val errorMsg = "Gemini API Error: ${serverMsg.error.message ?: serverMsg.error.status ?: "code ${serverMsg.error.code}"}"
                    AppLogger.e(TAG, errorMsg)
                    _events.tryEmit(LiveSocketEvent.Error(IOException(errorMsg), errorMsg))
                    return@launch
                }

                if (serverMsg.setupComplete != null) {
                    AppLogger.i(TAG, "Setup acknowledged by server")
                    _events.tryEmit(LiveSocketEvent.SetupAcknowledged)
                }

                serverMsg.serverContent?.let { content ->
                    if (content.interrupted) {
                        AppLogger.d(TAG, "Server barge-in interruption detected")
                        _events.tryEmit(LiveSocketEvent.Interrupted)
                    }

                    content.modelTurn?.parts?.forEach { part ->
                        part.text?.takeIf { it.isNotEmpty() }?.let { text ->
                            _events.tryEmit(LiveSocketEvent.TextReceived(text))
                        }

                        part.inlineData?.takeIf { it.data.isNotEmpty() }?.let { blob ->
                            try {
                                val pcmBytes = DtoMappers.decodeBase64(blob.data)
                                _events.tryEmit(LiveSocketEvent.AudioDataReceived(pcmBytes))
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Failed decoding server audio base64", e)
                            }
                        }
                    }

                    if (content.turnComplete) {
                        _events.tryEmit(LiveSocketEvent.TurnCompleted(true))
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error parsing server WebSocket message: $jsonText", e)
                _events.tryEmit(LiveSocketEvent.Error(e, "Message parse error: ${e.message}"))
            }
        }
    }

    /**
     * Disconnects the WebSocket and cleans up coroutine scopes.
     */
    @Synchronized
    fun disconnect() {
        _isConnected.set(false)
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
    }
}
