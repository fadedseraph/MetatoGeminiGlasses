package com.metatogemini.glasses.data

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import com.metatogemini.glasses.data.repository.LiveSessionRepositoryImpl
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionRepositoryImplTest {

    private val liveWebSocket: GeminiLiveWebSocket = mockk(relaxed = true)
    private val socketEventsFlow = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 64)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchersProvider = TestDispatchersProvider(testDispatcher)
    private lateinit var repository: LiveSessionRepositoryImpl

    private val testConfig = SessionConfig(
        apiKey = "live-api-key",
        model = "gemini-2.0-flash-exp",
        voice = "Puck",
        systemInstruction = "Live AI"
    )

    @Before
    fun setup() {
        every { liveWebSocket.events } returns socketEventsFlow
        repository = LiveSessionRepositoryImpl(
            liveWebSocket = liveWebSocket,
            dispatchersProvider = dispatchersProvider
        )
    }

    @Test
    fun `startSession triggers webSocket connect and updates connection state`() = runTest(testDispatcher) {
        repository.startSession(testConfig)

        verify(exactly = 1) {
            liveWebSocket.connect(
                apiKey = "live-api-key",
                model = "gemini-2.0-flash-exp",
                voiceName = "Puck",
                systemInstruction = "Live AI"
            )
        }
        assertEquals(ConnectionState.Connecting, repository.connectionState.value)
    }

    @Test
    fun `socket events translate to connection state and incoming messages`() = runTest(testDispatcher) {
        repository.startSession(testConfig)

        repository.incomingMessages.test {
            // Setup Acknowledged
            socketEventsFlow.emit(LiveSocketEvent.SetupAcknowledged)
            assertEquals(ConnectionState.Connected, repository.connectionState.value)
            val msg1 = awaitItem()
            assertTrue(msg1 is GeminiMessage.SetupComplete)

            // Text Received
            socketEventsFlow.emit(LiveSocketEvent.TextReceived("Hello wearer!"))
            val msg2 = awaitItem()
            assertTrue(msg2 is GeminiMessage.TextData)
            assertEquals("Hello wearer!", (msg2 as GeminiMessage.TextData).text)

            // Audio Received
            val pcmAudio = byteArrayOf(1, 2, 3, 4)
            socketEventsFlow.emit(LiveSocketEvent.AudioDataReceived(pcmAudio))
            val msg3 = awaitItem()
            assertTrue(msg3 is GeminiMessage.AudioData)
            assertArrayEquals(pcmAudio, (msg3 as GeminiMessage.AudioData).pcmBytes)

            // Interruption
            socketEventsFlow.emit(LiveSocketEvent.Interrupted)
            val msg4 = awaitItem()
            assertTrue(msg4 is GeminiMessage.Interruption)

            // Turn Complete
            socketEventsFlow.emit(LiveSocketEvent.TurnCompleted(true))
            val msg5 = awaitItem()
            assertTrue(msg5 is GeminiMessage.TurnComplete)

            // Error Event
            val error = RuntimeException("Socket error")
            socketEventsFlow.emit(LiveSocketEvent.Error(error, "Socket error"))
            assertTrue(repository.connectionState.value is ConnectionState.Failed)
            val msg6 = awaitItem()
            assertTrue(msg6 is GeminiMessage.Error)

            // Disconnected
            socketEventsFlow.emit(LiveSocketEvent.Disconnected)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendAudioChunk base64 encodes and delegates to socket`() = runTest(testDispatcher) {
        val pcm = byteArrayOf(10, 20, 30)
        repository.sendAudioChunk(pcm, 16000)

        verify(exactly = 1) { liveWebSocket.sendAudioChunk(any()) }
    }

    @Test
    fun `sendVideoFrame base64 encodes and delegates to socket`() = runTest(testDispatcher) {
        val jpeg = byteArrayOf(-1, -40, -1, -32)
        repository.sendVideoFrame(jpeg)

        verify(exactly = 1) { liveWebSocket.sendVideoFrame(any()) }
    }

    @Test
    fun `sendTextMessage sends text and emits user message`() = runTest(testDispatcher) {
        repository.incomingMessages.test {
            repository.sendTextMessage("User query")

            val message = awaitItem()
            assertTrue(message is GeminiMessage.TextData)
            assertEquals("User query", (message as GeminiMessage.TextData).text)
            assertTrue((message as GeminiMessage.TextData).isFromUser)

            verify(exactly = 1) { liveWebSocket.sendTextMessage("User query") }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stopSession disconnects socket and resets state`() = runTest(testDispatcher) {
        repository.startSession(testConfig)
        repository.stopSession()

        verify(atLeast = 1) { liveWebSocket.disconnect() }
        assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
    }
}
