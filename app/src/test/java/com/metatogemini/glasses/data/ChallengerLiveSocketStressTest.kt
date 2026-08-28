package com.metatogemini.glasses.data

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.GeminiLiveClientMessage
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import com.metatogemini.glasses.data.repository.LiveSessionRepositoryImpl
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerLiveSocketStressTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val dispatchersProvider = DefaultDispatchersProvider()
    private val mockOkHttpClient = mockk<OkHttpClient>(relaxed = true)

    @Test
    fun `empirical challenge - Base64 boundary padding and alignment checks`() {
        val testSizes = listOf(0, 1, 2, 3, 4, 5, 10, 512, 1024, 2048, 48000)
        for (size in testSizes) {
            val originalBytes = ByteArray(size) { (it % 256).toByte() }
            val encoded = DtoMappers.encodeBase64(originalBytes)
            val decoded = DtoMappers.decodeBase64(encoded)
            assertArrayEquals("Mismatch for byte array of size $size", originalBytes, decoded)
        }
    }

    @Test
    fun `empirical challenge - GeminiLiveWebSocket sendSetup formats model string and voice correctly`() {
        val mockWs = mockk<WebSocket>(relaxed = true)
        val capturedJson = slot<String>()
        every { mockWs.send(capture(capturedJson)) } returns true

        val liveWs = GeminiLiveWebSocket(
            okHttpClient = mockOkHttpClient,
            json = json,
            dispatchersProvider = dispatchersProvider
        )

        // Reflection injection of active webSocket for unit testing sendSetup directly
        val wsField = GeminiLiveWebSocket::class.java.getDeclaredField("webSocket").apply { isAccessible = true }
        wsField.set(liveWs, mockWs)

        // Case 1: Plain model name without "models/"
        liveWs.sendSetup(
            model = "gemini-2.0-flash-exp",
            voiceName = "Aoede",
            systemInstruction = "Concise assistant."
        )

        assertTrue(capturedJson.isCaptured)
        val parsed1 = json.decodeFromString<GeminiLiveClientMessage>(capturedJson.captured)
        assertEquals("models/gemini-2.0-flash-exp", parsed1.setup?.model)
        assertEquals("Aoede", parsed1.setup?.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
        assertEquals("Concise assistant.", parsed1.setup?.systemInstruction?.parts?.first()?.text)

        // Case 2: Model name already having "models/" prefix
        liveWs.sendSetup(
            model = "models/gemini-1.5-flash",
            voiceName = "Charon",
            systemInstruction = ""
        )

        val parsed2 = json.decodeFromString<GeminiLiveClientMessage>(capturedJson.captured)
        assertEquals("models/gemini-1.5-flash", parsed2.setup?.model)
        assertEquals("Charon", parsed2.setup?.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
        assertNull(parsed2.setup?.systemInstruction) // Blank instruction becomes null
    }

    @Test
    fun `empirical challenge - LiveSessionRepository full event lifecycle handling`() = runTest(UnconfinedTestDispatcher()) {
        val testDispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
        val liveWs = GeminiLiveWebSocket(
            okHttpClient = mockOkHttpClient,
            json = json,
            dispatchersProvider = testDispatchers
        )
        val repository = LiveSessionRepositoryImpl(
            liveWebSocket = liveWs,
            dispatchersProvider = testDispatchers
        )

        val config = SessionConfig(
            apiKey = "valid-test-key",
            model = "gemini-2.0-flash-exp",
            voice = "Puck"
        )

        // Reflection to trigger events directly on liveWs._events for deterministic lifecycle simulation
        val eventsField = GeminiLiveWebSocket::class.java.getDeclaredField("_events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val internalEvents = eventsField.get(liveWs) as kotlinx.coroutines.flow.MutableSharedFlow<LiveSocketEvent>

        repository.incomingMessages.test {
            repository.startSession(config)
            assertEquals(ConnectionState.Connecting, repository.connectionState.value)

            // 1. Setup acknowledged -> Connected
            internalEvents.emit(LiveSocketEvent.SetupAcknowledged)
            val msg1 = awaitItem()
            assertTrue(msg1 is GeminiMessage.SetupComplete)
            assertEquals(ConnectionState.Connected, repository.connectionState.value)

            // 2. Text received
            internalEvents.emit(LiveSocketEvent.TextReceived("Hello through glasses"))
            val msg2 = awaitItem()
            assertTrue(msg2 is GeminiMessage.TextData)
            assertEquals("Hello through glasses", (msg2 as GeminiMessage.TextData).text)
            assertFalse((msg2 as GeminiMessage.TextData).isFromUser)

            // 3. Audio received (24kHz)
            val audioBytes = byteArrayOf(1, 2, 3, 4)
            internalEvents.emit(LiveSocketEvent.AudioDataReceived(audioBytes))
            val msg3 = awaitItem()
            assertTrue(msg3 is GeminiMessage.AudioData)
            assertArrayEquals(audioBytes, (msg3 as GeminiMessage.AudioData).pcmBytes)
            assertEquals(24000, (msg3 as GeminiMessage.AudioData).sampleRate)

            // 4. Interrupted
            internalEvents.emit(LiveSocketEvent.Interrupted)
            val msg4 = awaitItem()
            assertTrue(msg4 is GeminiMessage.Interruption)

            // 5. Turn Complete
            internalEvents.emit(LiveSocketEvent.TurnCompleted(true))
            val msg5 = awaitItem()
            assertTrue(msg5 is GeminiMessage.TurnComplete)

            // 6. Error event
            val testError = IOException("Socket connection reset")
            internalEvents.emit(LiveSocketEvent.Error(testError, "Socket connection reset"))
            val msg6 = awaitItem()
            assertTrue(msg6 is GeminiMessage.Error)
            assertTrue(repository.connectionState.value is ConnectionState.Failed)

            // 7. Disconnect
            internalEvents.emit(LiveSocketEvent.Disconnected)
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)

            repository.stopSession()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
