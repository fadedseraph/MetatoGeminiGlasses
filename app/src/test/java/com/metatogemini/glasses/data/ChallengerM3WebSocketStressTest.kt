package com.metatogemini.glasses.data

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.DtoMappers.toRestRequest
import com.metatogemini.glasses.data.network.dto.DtoMappers.toSnapshotResponse
import com.metatogemini.glasses.data.network.dto.GeminiLiveClientMessage
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerMessage
import com.metatogemini.glasses.data.network.dto.GeminiRestResponse
import com.metatogemini.glasses.data.network.rest.GeminiRestClient
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import com.metatogemini.glasses.data.repository.LiveSessionRepositoryImpl
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.usecase.ProcessInterruptionUseCase
import com.metatogemini.glasses.domain.usecase.StopLiveSessionUseCase
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Adversarial Stress Suite for Milestone 3.
 * Rigorously stresses:
 * 1. Live WebSocket lifecycle (concurrent connect, disconnect, reconnect, error transitions).
 * 2. Interruption protocol (barge-in parsing, playback buffer purge, rapid interruption spam).
 * 3. Concurrent media packet delivery (audio, video, text racing across multiple threads).
 * 4. Downlink resilience (corrupted base64, malformed/truncated JSON, unexpected schemas).
 * 5. Dynamic model routing & Multimodal REST vision stress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerM3WebSocketStressTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val dispatchersProvider = DefaultDispatchersProvider()
    private lateinit var mockOkHttpClient: OkHttpClient
    private lateinit var mockWebSocket: WebSocket
    private val capturedListeners = mutableListOf<WebSocketListener>()
    private val capturedListener: WebSocketListener get() = capturedListeners.last()

    @Before
    fun setup() {
        mockOkHttpClient = mockk(relaxed = true)
        mockWebSocket = mockk(relaxed = true)
        capturedListeners.clear()

        val mockBuilder = mockk<OkHttpClient.Builder>(relaxed = true)
        every { mockOkHttpClient.newBuilder() } returns mockBuilder
        every { mockBuilder.pingInterval(any<Long>(), any()) } returns mockBuilder
        every { mockBuilder.readTimeout(any<Long>(), any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockOkHttpClient

        every {
            mockOkHttpClient.newWebSocket(any(), capture(capturedListeners))
        } returns mockWebSocket
    }

    // ========================================================================
    // 1. Live WebSocket Lifecycle Stress Tests
    // ========================================================================

    @Test
    fun `lifecycle_blank_or_whitespace_api_keys_emit_error_and_do_not_connect`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        val invalidKeys = listOf("", "   ", "\t", "\n", " \t \n ")

        for (key in invalidKeys) {
            client.events.test {
                client.connect(apiKey = key)
                val event = awaitItem()
                assertTrue("Expected Error event for blank key: '$key'", event is LiveSocketEvent.Error)
                assertEquals("Gemini API key cannot be blank", (event as LiveSocketEvent.Error).message)
                assertFalse(client.isConnected)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `lifecycle_successful_connection_transmits_exact_setup_handshake`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        val sentPayloads = mutableListOf<String>()
        every { mockWebSocket.send(capture(sentPayloads)) } returns true

        client.connect(
            apiKey = "test-api-key",
            model = "gemini-2.0-flash-exp",
            voiceName = "Puck",
            systemInstruction = "You are a smart glasses assistant."
        )

        assertTrue(capturedListeners.isNotEmpty())
        val listener = capturedListener

        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

        client.events.test {
            listener.onOpen(mockWebSocket, mockResponse)
            val event = awaitItem()
            assertEquals(LiveSocketEvent.Connected, event)
            assertTrue(client.isConnected)

            assertEquals(1, sentPayloads.size)
            val setupJson = sentPayloads[0]
            val parsedSetup = json.decodeFromString<GeminiLiveClientMessage>(setupJson)
            assertNotNull(parsedSetup.setup)
            assertEquals("models/gemini-2.0-flash-exp", parsedSetup.setup?.model)
            assertEquals("Puck", parsedSetup.setup?.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName)
            assertEquals("You are a smart glasses assistant.", parsedSetup.setup?.systemInstruction?.parts?.firstOrNull()?.text)
            assertEquals(listOf("AUDIO"), parsedSetup.setup?.generationConfig?.responseModalities)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lifecycle_reconnect_during_active_connection_cleans_up_and_restarts`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        every { mockWebSocket.send(any<String>()) } returns true

        // First connection
        client.connect(apiKey = "key-1", model = "gemini-2.0-flash-exp")
        assertTrue(capturedListeners.isNotEmpty())
        val listener1 = capturedListeners[0]

        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        listener1.onOpen(mockWebSocket, mockResponse)
        assertTrue(client.isConnected)

        // Reconnect with different config
        client.connect(apiKey = "key-2", model = "gemini-1.5-flash")
        verify(atLeast = 1) { mockWebSocket.close(1000, any()) }

        val listener2 = capturedListeners.last()
        listener2.onOpen(mockWebSocket, mockResponse)
        assertTrue(client.isConnected)
    }

    @Test
    fun `lifecycle_concurrent_multi_threaded_connect_and_disconnect_cycles`() {
        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)

        for (i in 0 until threadCount) {
            pool.submit {
                try {
                    for (step in 1..25) {
                        if (step % 2 == 0) {
                            client.connect(apiKey = "key-$step", model = "gemini-2.0-flash-exp")
                        } else {
                            client.disconnect()
                        }
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent connect/disconnect must complete without deadlock", completed)
        assertEquals(0, errorCount.get())
        client.disconnect()
        assertFalse(client.isConnected)
        pool.shutdownNow()
    }

    @Test
    fun `lifecycle_onFailure_and_onClosed_emit_error_and_disconnected_events`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        client.events.test {
            val failureEx = IOException("Broken pipe")
            listener.onFailure(mockWebSocket, failureEx, null as Response?)

            val event1 = awaitItem()
            assertTrue(event1 is LiveSocketEvent.Error)
            assertEquals("Broken pipe", (event1 as LiveSocketEvent.Error).throwable.message)

            val event2 = awaitItem()
            assertEquals(LiveSocketEvent.Disconnected, event2)
            assertFalse(client.isConnected)

            // Simulate onClosed
            listener.onClosed(mockWebSocket, 1000, "Clean Exit")
            val event3 = awaitItem()
            assertEquals(LiveSocketEvent.Disconnected, event3)
            assertFalse(client.isConnected)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // 2. Interruption & Low-Latency Barge-In Stress Tests
    // ========================================================================

    @Test
    fun `interruption_server_interrupted_true_triggers_interrupted_event_and_playback_purge`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        val mockPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)
        val processInterruption = ProcessInterruptionUseCase(mockPlaybackManager)

        client.events.test {
            val interruptedJson = """
                {
                    "serverContent": {
                        "interrupted": true
                    }
                }
            """.trimIndent()

            listener.onMessage(mockWebSocket, interruptedJson)

            val event = awaitItem()
            assertEquals(LiveSocketEvent.Interrupted, event)

            // Trigger barge in usecase
            processInterruption()
            verify(exactly = 1) { mockPlaybackManager.stopAndClear() }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `interruption_stop_live_session_use_case_purges_audio_and_stops_repository`() = runTest {
        val mockRepo: com.metatogemini.glasses.domain.repository.LiveSessionRepository = mockk(relaxed = true)
        val mockPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)
        val stopUseCase = StopLiveSessionUseCase(mockRepo, mockPlaybackManager)

        stopUseCase()

        verify(exactly = 1) { mockPlaybackManager.stopAndClear() }
        coVerify(exactly = 1) { mockRepo.stopSession() }
    }

    @Test
    fun `interruption_high_frequency_burst_spam_handled_without_loss`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        val burstCount = 50
        val interruptedJson = """{"serverContent":{"interrupted":true}}"""

        client.events.test {
            for (i in 1..burstCount) {
                listener.onMessage(mockWebSocket, interruptedJson)
            }

            for (i in 1..burstCount) {
                val event = awaitItem()
                assertEquals(LiveSocketEvent.Interrupted, event)
            }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `interruption_mixed_server_payload_extracts_interruption_text_audio_and_turn_complete`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        val rawPcm = byteArrayOf(10, 20, 30, 40, 50)
        val base64Pcm = Base64.getEncoder().encodeToString(rawPcm)

        val mixedPayload = """
            {
                "serverContent": {
                    "interrupted": true,
                    "modelTurn": {
                        "parts": [
                            {"text": "Understood, shifting topic."},
                            {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$base64Pcm"}}
                        ]
                    },
                    "turnComplete": true
                }
            }
        """.trimIndent()

        client.events.test {
            listener.onMessage(mockWebSocket, mixedPayload)

            val event1 = awaitItem()
            assertEquals(LiveSocketEvent.Interrupted, event1)

            val event2 = awaitItem()
            assertTrue(event2 is LiveSocketEvent.TextReceived)
            assertEquals("Understood, shifting topic.", (event2 as LiveSocketEvent.TextReceived).text)

            val event3 = awaitItem()
            assertTrue(event3 is LiveSocketEvent.AudioDataReceived)
            assertArrayEquals(rawPcm, (event3 as LiveSocketEvent.AudioDataReceived).pcm24kBytes)

            val event4 = awaitItem()
            assertEquals(LiveSocketEvent.TurnCompleted(true), event4)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // 3. Concurrent Media Packet Delivery & Uplink Stress Tests
    // ========================================================================

    @Test
    fun `uplink_concurrent_audio_video_and_text_streaming_across_threads`() {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        val capturedMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        every { mockWebSocket.send(any<String>()) } answers {
            capturedMessages.add(firstArg())
            true
        }

        // Simulate open socket
        client.connect(apiKey = "test-key")
        val listener = capturedListener
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        listener.onOpen(mockWebSocket, mockResponse)
        assertTrue(client.isConnected)

        val pool = Executors.newFixedThreadPool(8)
        val threadCount = 6
        val operationsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        val sampleAudioPcmBase64 = Base64.getEncoder().encodeToString(ByteArray(1024) { 1 })
        val sampleJpegBase64 = Base64.getEncoder().encodeToString(ByteArray(2048) { -1 })

        // 2 Audio sender threads
        for (i in 0 until 2) {
            pool.submit {
                try {
                    for (op in 1..operationsPerThread) {
                        client.sendAudioChunk(sampleAudioPcmBase64)
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 2 Video frame sender threads
        for (i in 0 until 2) {
            pool.submit {
                try {
                    for (op in 1..operationsPerThread) {
                        client.sendVideoFrame(sampleJpegBase64)
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 2 Text sender threads
        for (i in 0 until 2) {
            pool.submit {
                try {
                    for (op in 1..operationsPerThread) {
                        client.sendTextMessage("User query item $op")
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent uplink must finish without blocking", completed)
        assertEquals(0, errorCount.get())

        // 1 setup message + (6 threads * 50 operations = 300) = 301 messages
        assertEquals(301, capturedMessages.size)

        // Validate all captured messages deserialize cleanly as GeminiLiveClientMessage
        for (msg in capturedMessages) {
            val parsed = json.decodeFromString<GeminiLiveClientMessage>(msg)
            assertTrue(parsed.setup != null || parsed.realtimeInput != null || parsed.clientContent != null)
        }

        pool.shutdownNow()
    }

    @Test
    fun `uplink_empty_or_blank_media_packets_are_ignored_and_do_not_send`() {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        val capturedMessages = mutableListOf<String>()
        every { mockWebSocket.send(capture(capturedMessages)) } returns true

        // Not connected: calls are safe no-ops
        client.sendAudioChunk("")
        client.sendAudioChunk("   ")
        client.sendVideoFrame("")
        client.sendVideoFrame("   ")
        client.sendTextMessage("")
        client.sendTextMessage("   ")
        assertEquals(0, capturedMessages.size)

        // Connected: blank data still ignored
        client.connect(apiKey = "key")
        val listener = capturedListener
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
        listener.onOpen(mockWebSocket, mockResponse)

        capturedMessages.clear()
        client.sendAudioChunk("")
        client.sendVideoFrame("")
        client.sendTextMessage("")
        assertEquals(0, capturedMessages.size)
    }

    // ========================================================================
    // 4. Downlink Malformed Payloads & Robustness
    // ========================================================================

    @Test
    fun `downlink_malformed_or_truncated_json_emits_error_and_does_not_crash`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        val malformedStrings = listOf(
            "",
            "   ",
            "{",
            "{ \"serverContent\": ",
            "NOT_JSON_AT_ALL",
            "{\"unknownField\": 12345}"
        )

        client.events.test {
            for (badJson in malformedStrings) {
                listener.onMessage(mockWebSocket, badJson)
            }

            // Expect error events for the truly malformed JSONs
            var errorCount = 0
            for (i in 1..5) {
                val event = awaitItem()
                if (event is LiveSocketEvent.Error) {
                    errorCount++
                }
            }
            assertTrue("Should emit Error events on malformed JSON", errorCount >= 4)

            // Subsequent valid message is still processed normally
            val validAck = """{"setupComplete":{"model":"gemini-2.0-flash-exp"}}"""
            listener.onMessage(mockWebSocket, validAck)
            val ackEvent = awaitItem()
            assertEquals(LiveSocketEvent.SetupAcknowledged, ackEvent)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `downlink_invalid_base64_audio_blob_fails_gracefully_without_coroutine_cancellation`() = runTest {
        val client = GeminiLiveWebSocket(mockOkHttpClient, json, dispatchersProvider)
        client.connect(apiKey = "valid-key")
        val listener = capturedListener

        val badBase64Payload = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {"inlineData": {"mimeType": "audio/pcm", "data": "%%%NOT_VALID_BASE64%%%"}}
                        ]
                    }
                }
            }
        """.trimIndent()

        client.events.test {
            listener.onMessage(mockWebSocket, badBase64Payload)

            // Ensure channel didn't crash and can still receive subsequent text
            val validNextPayload = """
                {
                    "serverContent": {
                        "modelTurn": {
                            "parts": [{"text": "Recovered successfully."}]
                        }
                    }
                }
            """.trimIndent()
            listener.onMessage(mockWebSocket, validNextPayload)

            val textEvent = awaitItem()
            assertTrue(textEvent is LiveSocketEvent.TextReceived)
            assertEquals("Recovered successfully.", (textEvent as LiveSocketEvent.TextReceived).text)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // 5. LiveSessionRepositoryImpl Concurrency & State Machine Stress
    // ========================================================================

    @Test
    fun `repository_rapid_start_and_stop_cycles_maintain_clean_state`() = runTest(UnconfinedTestDispatcher()) {
        val mockSocket: GeminiLiveWebSocket = mockk(relaxed = true)
        val socketEvents = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 64)
        every { mockSocket.events } returns socketEvents

        val testDispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
        val repository = LiveSessionRepositoryImpl(mockSocket, testDispatchers)
        val config = SessionConfig(apiKey = "test-key")

        for (cycle in 1..20) {
            repository.startSession(config)
            assertEquals(ConnectionState.Connecting, repository.connectionState.value)

            socketEvents.emit(LiveSocketEvent.SetupAcknowledged)
            assertEquals(ConnectionState.Connected, repository.connectionState.value)

            repository.stopSession()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        }
    }

    @Test
    fun `repository_socket_error_event_transitions_to_failed_state`() = runTest(UnconfinedTestDispatcher()) {
        val mockSocket: GeminiLiveWebSocket = mockk(relaxed = true)
        val socketEvents = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 64)
        every { mockSocket.events } returns socketEvents

        val testDispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
        val repository = LiveSessionRepositoryImpl(mockSocket, testDispatchers)
        val config = SessionConfig(apiKey = "test-key")

        repository.startSession(config)

        repository.incomingMessages.test {
            val ex = IOException("Connection reset by peer")
            socketEvents.emit(LiveSocketEvent.Error(ex, "Connection reset by peer"))

            assertTrue(repository.connectionState.value is ConnectionState.Failed)
            val failedState = repository.connectionState.value as ConnectionState.Failed
            assertEquals("Connection reset by peer", failedState.reason)

            val msg = awaitItem()
            assertTrue(msg is GeminiMessage.Error)
            assertEquals("Connection reset by peer", (msg as GeminiMessage.Error).message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // 6. GeminiRestClient Multimodal Vision Stress Tests
    // ========================================================================

    @Test
    fun `rest_empty_image_or_blank_api_key_returns_result_error`() = runTest {
        val restClient = GeminiRestClient(mockOkHttpClient, json, dispatchersProvider)

        val err1 = restClient.analyzeSnapshot(apiKey = "", model = "gemini-1.5-flash", request = SnapshotRequest(imageBytes = byteArrayOf(1, 2)))
        assertTrue(err1 is Result.Error)
        assertEquals("API key is missing", (err1 as Result.Error).message)

        val err2 = restClient.analyzeSnapshot(apiKey = "valid-key", model = "gemini-1.5-flash", request = SnapshotRequest(imageBytes = byteArrayOf()))
        assertTrue(err2 is Result.Error)
        assertEquals("Image data is empty", (err2 as Result.Error).message)
    }

    @Test
    fun `rest_successful_response_calculates_latency_and_extracts_candidate_text`() = runTest {
        val mockCall: Call = mockk()
        val responseBodyString = """
            {
                "candidates": [
                    {
                        "content": {
                            "role": "model",
                            "parts": [
                                {"text": "A close-up view of a smart display terminal."}
                            ]
                        },
                        "finishReason": "STOP"
                    }
                ]
            }
        """.trimIndent()

        val mockHttpResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBodyString.toResponseBody("application/json".toMediaType()))
            .build()

        every { mockOkHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockHttpResponse

        val restClient = GeminiRestClient(mockOkHttpClient, json, dispatchersProvider)
        val snapshotRequest = SnapshotRequest(
            imageBytes = byteArrayOf(-1, -40, -1, -32, 0, 16),
            prompt = "What is this item?"
        )

        val result = restClient.analyzeSnapshot(
            apiKey = "test-key",
            model = "gemini-2.0-flash-exp",
            request = snapshotRequest
        )

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("A close-up view of a smart display terminal.", data.text)
        assertEquals("STOP", data.finishReason)
        assertTrue("Latency should be recorded", data.latencyMs >= 0)
    }

    @Test
    fun `rest_http_400_and_500_errors_parsed_cleanly_into_result_error`() = runTest {
        val mockCall: Call = mockk()
        val errorJson = """
            {
                "error": {
                    "code": 400,
                    "message": "API key expired or invalid",
                    "status": "INVALID_ARGUMENT"
                }
            }
        """.trimIndent()

        val mockHttpResponse = Response.Builder()
            .request(Request.Builder().url("https://generativelanguage.googleapis.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body(errorJson.toResponseBody("application/json".toMediaType()))
            .build()

        every { mockOkHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockHttpResponse

        val restClient = GeminiRestClient(mockOkHttpClient, json, dispatchersProvider)
        val result = restClient.analyzeSnapshot(
            apiKey = "expired-key",
            model = "gemini-1.5-pro",
            request = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3))
        )

        assertTrue(result is Result.Error)
        assertEquals("API key expired or invalid", (result as Result.Error).message)
    }
}
