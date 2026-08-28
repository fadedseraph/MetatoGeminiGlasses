package com.metatogemini.glasses.mock

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiMockEngineTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchersProvider = TestDispatchersProvider(testDispatcher)
    private val toneGenerator = SyntheticToneGenerator()
    private lateinit var mockEngine: GeminiMockEngine

    private val testConfig = SessionConfig(
        apiKey = "mock-key",
        model = "gemini-2.0-flash-exp",
        voice = "Puck",
        systemInstruction = "Mock Smart Glasses AI",
        isMockMode = true
    )

    @Before
    fun setup() {
        mockEngine = GeminiMockEngine(
            syntheticToneGenerator = toneGenerator,
            dispatchersProvider = dispatchersProvider
        )
        mockEngine.setWordDelayMs(50L)
        mockEngine.setSetupDelayMs(20L)
    }

    @Test
    fun `initial state is Disconnected`() {
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun `startSession transitions state to Connected and emits SetupComplete`() = runTest(testDispatcher) {
        mockEngine.incomingMessages.test {
            mockEngine.startSession(testConfig)
            advanceTimeBy(30L)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)

            val msg = awaitItem()
            assertTrue("Expected SetupComplete message", msg is GeminiMessage.SetupComplete)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendTextMessage emits user message and streams assistant response turns`() = runTest(testDispatcher) {
        mockEngine.startSession(testConfig)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.sendTextMessage("What is in front of me?")

            // First item: user message echoed
            val userMsg = awaitItem()
            assertTrue(userMsg is GeminiMessage.TextData)
            assertEquals("What is in front of me?", (userMsg as GeminiMessage.TextData).text)
            assertTrue(userMsg.isFromUser)

            // Let mock engine stream tokens
            advanceUntilIdle()

            val textMessages = mutableListOf<GeminiMessage.TextData>()
            val audioMessages = mutableListOf<GeminiMessage.AudioData>()
            var turnCompleted = false

            while (true) {
                val item = try {
                    awaitItem()
                } catch (e: Throwable) {
                    break
                }
                when (item) {
                    is GeminiMessage.TextData -> {
                        assertFalse(item.isFromUser)
                        textMessages.add(item)
                    }
                    is GeminiMessage.AudioData -> {
                        assertEquals(24000, item.sampleRate)
                        assertTrue(item.pcmBytes.isNotEmpty())
                        audioMessages.add(item)
                    }
                    is GeminiMessage.TurnComplete -> {
                        assertTrue(item.isComplete)
                        turnCompleted = true
                        break
                    }
                    else -> {}
                }
            }

            assertTrue("Should have streamed multiple text tokens", textMessages.isNotEmpty())
            assertTrue("Should have streamed synchronized 24kHz audio chunks", audioMessages.isNotEmpty())
            assertTrue("Turn should be marked complete", turnCompleted)
            assertFalse(mockEngine.isStreaming())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendAudioChunk during active streaming triggers barge-in interruption`() = runTest(testDispatcher) {
        mockEngine.startSession(testConfig)
        mockEngine.setWordDelayMs(100L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.sendTextMessage("Describe the room in detail")
            val userMsg = awaitItem()
            assertTrue(userMsg is GeminiMessage.TextData)

            // Advance slightly to begin assistant streaming
            advanceTimeBy(100L)
            assertTrue(mockEngine.isStreaming())

            // Consume first word and audio
            val firstWord = awaitItem()
            assertTrue(firstWord is GeminiMessage.TextData)
            val firstAudio = awaitItem()
            assertTrue(firstAudio is GeminiMessage.AudioData)

            // User interrupts with speech
            val speechChunk = toneGenerator.generateTone(sampleRateHz = 16000, durationMs = 32, amplitude = 0.8)
            mockEngine.sendAudioChunk(speechChunk, 16000)
            advanceUntilIdle()

            val interruptMsg = awaitItem()
            assertTrue("Expected Interruption message", interruptMsg is GeminiMessage.Interruption)
            assertFalse(mockEngine.isStreaming())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerInterruption cancels active streaming immediately`() = runTest(testDispatcher) {
        mockEngine.startSession(testConfig)
        mockEngine.setWordDelayMs(200L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.triggerSimulatedResponse("A very long narrative simulation that should be interrupted")
            advanceTimeBy(50L)

            assertTrue(mockEngine.isStreaming())
            mockEngine.triggerInterruption()
            advanceUntilIdle()

            // Skip any initial token if emitted
            var receivedInterruption = false
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.Interruption) {
                    receivedInterruption = true
                    break
                }
            }

            assertTrue("Should have received Interruption message", receivedInterruption)
            assertFalse(mockEngine.isStreaming())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sendVideoFrame stores frame bytes without throwing`() = runTest(testDispatcher) {
        mockEngine.startSession(testConfig)
        advanceUntilIdle()

        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        mockEngine.sendVideoFrame(sampleJpeg)
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
    }

    @Test
    fun `analyzeSnapshot returns valid mock response with latency`() = runTest(testDispatcher) {
        val request = SnapshotRequest(
            imageBytes = byteArrayOf(1, 2, 3, 4, 5),
            prompt = "What is the battery level of my glasses?"
        )

        val result = mockEngine.analyzeSnapshot(request, testConfig)
        advanceUntilIdle()

        assertTrue("Snapshot analysis should succeed", result is Result.Success)
        val response = (result as Result.Success).data
        assertNotNull(response)
        assertTrue("Response should include prompt information", response.text.contains("battery"))
        assertTrue("Response text should describe mock multimodal analysis", response.text.contains("Offline Mock"))
        assertEquals("STOP", response.finishReason)
    }

    @Test
    fun `stopSession resets state to Disconnected and terminates jobs`() = runTest(testDispatcher) {
        mockEngine.startSession(testConfig)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)

        mockEngine.stopSession()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun `custom responses can be added and streamed`() = runTest(testDispatcher) {
        val customText = "Custom glasses prompt response test."
        mockEngine.addCustomResponses(listOf(customText))
        mockEngine.startSession(testConfig)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.triggerSimulatedResponse(customText)
            advanceUntilIdle()

            val tokens = mutableListOf<String>()
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.TextData) {
                    tokens.add(item.text)
                } else if (item is GeminiMessage.TurnComplete) {
                    break
                }
            }

            val combined = tokens.joinToString("").trim()
            assertEquals(customText, combined)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
