package com.metatogemini.glasses.data

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiLiveWebSocketTest {

    private val okHttpClient: OkHttpClient = mockk(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val dispatchersProvider = DefaultDispatchersProvider()
    private lateinit var liveWebSocket: GeminiLiveWebSocket

    @Before
    fun setup() {
        liveWebSocket = GeminiLiveWebSocket(
            okHttpClient = okHttpClient,
            json = json,
            dispatchersProvider = dispatchersProvider
        )
    }

    @Test
    fun `initial connection state is disconnected`() {
        assertFalse(liveWebSocket.isConnected)
    }

    @Test
    fun `connect with blank API key emits Error event`() = runTest {
        liveWebSocket.events.test {
            liveWebSocket.connect(apiKey = "")
            val event = awaitItem()
            assertTrue(event is LiveSocketEvent.Error)
            assertEquals("Gemini API key cannot be blank", (event as LiveSocketEvent.Error).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `send operations when not connected do not throw exceptions`() {
        liveWebSocket.sendAudioChunk("UklGRiQAAABXQVZF")
        liveWebSocket.sendVideoFrame("/9j/4AAQSkZJRg==")
        liveWebSocket.sendTextMessage("Test text")
        // Should complete silently without throwing exceptions
    }

    @Test
    fun `disconnect cleanly resets state`() {
        liveWebSocket.disconnect()
        assertFalse(liveWebSocket.isConnected)
    }
}
