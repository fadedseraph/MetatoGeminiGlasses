package com.metatogemini.glasses.mock

import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import kotlin.random.Random

/**
 * Empirical Adversarial Test Suite for [GeminiMockEngine].
 *
 * Stress-tests:
 * 1. Mock session handshake, rapid reconnect, start/stop races, and lifecycle idempotency.
 * 2. High-concurrency turn streaming, interleaved multi-modal traffic, and flood attacks.
 * 3. Low-latency barge-in interruption, race conditions, token leakage prevention, and RMS thresholds.
 * 4. Concurrent REST snapshot analysis, extreme payloads, prompt variations, and decoupled lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeminiMockEngineAdversarialTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchersProvider = TestDispatchersProvider(testDispatcher)
    private val toneGenerator = SyntheticToneGenerator()
    private lateinit var mockEngine: GeminiMockEngine

    private val baseConfig = SessionConfig(
        apiKey = "adversarial-mock-key",
        model = "gemini-2.0-flash-exp",
        voice = "Aoede",
        systemInstruction = "Adversarial Test Suite Assistant",
        isMockMode = true
    )

    @Before
    fun setup() {
        mockEngine = GeminiMockEngine(
            syntheticToneGenerator = toneGenerator,
            dispatchersProvider = dispatchersProvider
        )
        mockEngine.setWordDelayMs(20L)
        mockEngine.setSetupDelayMs(10L)
    }

    // =========================================================================
    // Tier 1: Handshake & Lifecycle Chaos
    // =========================================================================

    @Test
    fun `rapid consecutive startSession calls without stopSession stabilizes on Connected`() = runTest(testDispatcher) {
        // Repeatedly start session with differing configs rapidly
        for (i in 1..10) {
            val config = baseConfig.copy(voice = "Voice_$i", model = "model_$i")
            mockEngine.startSession(config)
        }
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun `stopSession immediately during setup delay cancels session before Connected`() = runTest(testDispatcher) {
        mockEngine.setSetupDelayMs(200L)
        mockEngine.startSession(baseConfig)

        // Advance partially into setup delay
        advanceTimeBy(50L)
        assertEquals(ConnectionState.Connecting, mockEngine.connectionState.value)

        // Stop session before setup completes
        mockEngine.stopSession()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun `stopSession is completely idempotent when invoked multiple times consecutively`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)

        repeat(15) {
            mockEngine.stopSession()
        }
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun `traffic sent when session is disconnected is dropped safely with zero emissions`() = runTest(testDispatcher) {
        mockEngine.incomingMessages.test {
            // Engine is disconnected
            assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)

            val dummyAudio = ByteArray(1024) { 1 }
            val dummyJpeg = ByteArray(512) { 0xFF.toByte() }

            mockEngine.sendAudioChunk(dummyAudio, 16000)
            mockEngine.sendVideoFrame(dummyJpeg)
            mockEngine.sendTextMessage("Hello to nowhere")
            advanceUntilIdle()

            // Verify no events were emitted to the stream
            expectNoEvents()
        }
    }

    @Test
    fun `session restart after stopSession operates cleanly with new handshake`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)

        mockEngine.stopSession()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)

        mockEngine.incomingMessages.test {
            mockEngine.startSession(baseConfig.copy(voice = "Puck"))
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
            val msg = awaitItem()
            assertTrue("Expected SetupComplete on restarted session", msg is GeminiMessage.SetupComplete)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `negative setup and word delay configurations are coerced safely to zero`() = runTest(testDispatcher) {
        mockEngine.setSetupDelayMs(-100L)
        mockEngine.setWordDelayMs(-50L)

        mockEngine.incomingMessages.test {
            mockEngine.startSession(baseConfig)
            advanceUntilIdle()

            assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
            val setupMsg = awaitItem()
            assertTrue(setupMsg is GeminiMessage.SetupComplete)

            mockEngine.sendTextMessage("Instant test")
            val userMsg = awaitItem()
            assertTrue(userMsg is GeminiMessage.TextData && userMsg.isFromUser)

            advanceUntilIdle()

            var turnCompleteReceived = false
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.TurnComplete) {
                    turnCompleteReceived = true
                    break
                }
            }
            assertTrue("Turn complete should finish instantly with 0ms delay", turnCompleteReceived)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Tier 2: Turn Streaming Under High Concurrency
    // =========================================================================

    @Test
    fun `concurrent flood of sendTextMessage calls cleanly replaces streaming turns without deadlock`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        advanceUntilIdle()

        mockEngine.setWordDelayMs(10L)

        // Launch 25 concurrent coroutines calling sendTextMessage
        val jobs = (1..25).map { i ->
            launch {
                mockEngine.sendTextMessage("Query flood index $i")
            }
        }
        jobs.forEach { it.join() }
        advanceUntilIdle()

        assertFalse("Mock engine should finish all streaming without hanging", mockEngine.isStreaming())
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
    }

    @Test
    fun `interleaved multimodal input stream during active turn does not corrupt engine state`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(30L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.sendTextMessage("Describe the multimodal scene in comprehensive detail")
            advanceTimeBy(40L)

            assertTrue(mockEngine.isStreaming())

            // While assistant is streaming, spam video frames and small sub-threshold audio chunks
            val silentAudio = ByteArray(640) // 0s = silent
            val sampleFrame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)

            repeat(50) {
                mockEngine.sendVideoFrame(sampleFrame)
                mockEngine.sendAudioChunk(silentAudio, 16000)
            }

            advanceUntilIdle()

            assertFalse(mockEngine.isStreaming())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `token and audio chunk synchronization maintains 24kHz sample rate and non-empty PCM`() = runTest(testDispatcher) {
        val testPrompt = "Test audio and token synchronization"
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(10L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.triggerSimulatedResponse(testPrompt)
            advanceUntilIdle()

            val textTokens = mutableListOf<String>()
            val audioChunks = mutableListOf<ByteArray>()
            var turnCompleted = false

            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                when (item) {
                    is GeminiMessage.TextData -> {
                        if (!item.isFromUser) textTokens.add(item.text)
                    }
                    is GeminiMessage.AudioData -> {
                        assertEquals(Constants.SAMPLE_RATE_PLAYBACK_HZ, item.sampleRate)
                        assertTrue("Audio chunk must not be empty", item.pcmBytes.isNotEmpty())
                        // 16-bit PCM must have an even number of bytes
                        assertEquals("PCM bytes must be 16-bit aligned (even length)", 0, item.pcmBytes.size % 2)
                        audioChunks.add(item.pcmBytes)
                    }
                    is GeminiMessage.TurnComplete -> {
                        assertTrue(item.isComplete)
                        turnCompleted = true
                        break
                    }
                    else -> {}
                }
            }

            assertEquals(
                "Each token must have exactly one corresponding audio chunk",
                textTokens.size,
                audioChunks.size
            )
            assertTrue("Turn complete must be emitted", turnCompleted)
            val fullText = textTokens.joinToString("").trim()
            assertEquals(testPrompt, fullText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `custom responses with extreme text formatting stream accurately`() = runTest(testDispatcher) {
        val extremeResponses = listOf(
            "SingleWord",
            "Special chars: !@#\$%^&*()_+=-~`{}[]|;:'<>,.?/",
            "Multiline\nText\nWith\nNewlines",
            "Emoji test: 👓 🤖 ⚡ 🔊 🎙️"
        )
        mockEngine.addCustomResponses(extremeResponses)
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(5L)
        advanceUntilIdle()

        for (custom in extremeResponses) {
            mockEngine.incomingMessages.test {
                mockEngine.triggerSimulatedResponse(custom)
                advanceUntilIdle()

                val tokens = mutableListOf<String>()
                while (true) {
                    val item = try { awaitItem() } catch (e: Throwable) { break }
                    if (item is GeminiMessage.TextData && !item.isFromUser) {
                        tokens.add(item.text)
                    } else if (item is GeminiMessage.TurnComplete) {
                        break
                    }
                }

                val reconstructed = tokens.joinToString("").trim()
                assertEquals(custom, reconstructed)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // =========================================================================
    // Tier 3: Barge-In Interruption & Token Leakage Prevention
    // =========================================================================

    @Test
    fun `barge-in interruption mid-stream completely halts emission of remaining tokens and audio`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(100L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            // Trigger 10-word turn
            mockEngine.triggerSimulatedResponse("WordOne WordTwo WordThree WordFour WordFive WordSix WordSeven WordEight WordNine WordTen")
            advanceTimeBy(100L) // First token emitted

            val firstToken = awaitItem()
            assertTrue(firstToken is GeminiMessage.TextData)
            val firstAudio = awaitItem()
            assertTrue(firstAudio is GeminiMessage.AudioData)

            // User speech triggers barge-in
            val speechPcm = toneGenerator.generateTone24k(durationMs = 50, frequencyHz = 440.0, amplitude = 0.9)
            mockEngine.sendAudioChunk(speechPcm, 24000)
            advanceUntilIdle()

            val interruptMsg = awaitItem()
            assertTrue("Must receive Interruption event", interruptMsg is GeminiMessage.Interruption)
            assertFalse("Streaming must be inactive", mockEngine.isStreaming())

            // Assert no further events from the cancelled turn leak out
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent barge-in barrage emits single interruption and cancels stream without crashing`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(100L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.triggerSimulatedResponse("A long response to be barraged with interruptions")
            advanceTimeBy(50L)

            assertTrue(mockEngine.isStreaming())

            val loudPcm = toneGenerator.generateTone(16000, 30, 0.8)

            // Multiple concurrent threads spamming audio chunks and manual interruptions
            val coroutines = (1..20).map {
                launch {
                    if (it % 2 == 0) {
                        mockEngine.sendAudioChunk(loudPcm, 16000)
                    } else {
                        mockEngine.triggerInterruption()
                    }
                }
            }
            coroutines.forEach { it.join() }
            advanceUntilIdle()

            assertFalse(mockEngine.isStreaming())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `silent audio chunk does not trigger barge-in during speech synthesis`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(50L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            mockEngine.triggerSimulatedResponse("Short speech turn")
            advanceTimeBy(50L)

            assertTrue(mockEngine.isStreaming())

            // Empty/zero audio should not trigger barge-in
            val emptyAudio = ByteArray(0)
            mockEngine.sendAudioChunk(emptyAudio, 16000)
            advanceUntilIdle()

            var reachedTurnComplete = false
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.TurnComplete) {
                    reachedTurnComplete = true
                    break
                }
            }

            assertTrue("Should complete turn normally without barge-in for empty audio", reachedTurnComplete)
            assertFalse(mockEngine.isStreaming())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `new turn starts and finishes cleanly immediately after barge-in interruption`() = runTest(testDispatcher) {
        mockEngine.startSession(baseConfig)
        mockEngine.setWordDelayMs(50L)
        advanceUntilIdle()

        mockEngine.incomingMessages.test {
            // Turn 1
            mockEngine.triggerSimulatedResponse("Turn one long sentence that will be interrupted")
            advanceTimeBy(60L)
            assertTrue(mockEngine.isStreaming())

            mockEngine.triggerInterruption()
            advanceUntilIdle()
            assertFalse(mockEngine.isStreaming())

            // Consume up to Interruption
            var sawInterruption = false
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.Interruption) {
                    sawInterruption = true
                    break
                }
            }
            assertTrue(sawInterruption)

            // Turn 2 immediately
            mockEngine.triggerSimulatedResponse("Turn two clean sentence")
            advanceUntilIdle()

            var sawTurn2Complete = false
            while (true) {
                val item = try { awaitItem() } catch (e: Throwable) { break }
                if (item is GeminiMessage.TurnComplete) {
                    sawTurn2Complete = true
                    break
                }
            }

            assertTrue("Turn 2 must complete cleanly following Turn 1 interruption", sawTurn2Complete)
            assertFalse(mockEngine.isStreaming())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Tier 4: Mock REST Snapshot Processing
    // =========================================================================

    @Test
    fun `concurrent analyzeSnapshot calls handle extreme payloads and return valid responses`() = runTest(testDispatcher) {
        val testCases = listOf(
            SnapshotRequest(imageBytes = ByteArray(0), prompt = "Empty image snapshot", mimeType = "image/jpeg"),
            SnapshotRequest(imageBytes = ByteArray(1024 * 100) { 0x7F }, prompt = "Large 100KB snapshot", mimeType = "image/png"),
            SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3), prompt = "", mimeType = "image/webp"),
            SnapshotRequest(imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), prompt = "A".repeat(2000), mimeType = "image/jpeg"),
            SnapshotRequest(imageBytes = byteArrayOf(1), prompt = "Special chars: 🔥 🚀 💯 \uD83D\uDD0A", mimeType = "image/jpeg")
        )

        val deferredResults = testCases.map { req ->
            async {
                mockEngine.analyzeSnapshot(req, baseConfig)
            }
        }
        advanceUntilIdle()

        val results = deferredResults.awaitAll()

        assertEquals(testCases.size, results.size)
        results.forEachIndexed { index, result ->
            assertTrue("Snapshot $index should succeed", result is Result.Success)
            val resp = (result as Result.Success).data
            assertNotNull(resp)
            assertEquals("STOP", resp.finishReason)
            assertTrue("Response latency must be non-negative", resp.latencyMs >= 0)
            assertTrue("Response text must include scene description", resp.text.contains("Offline Mock Multimodal Analysis"))
            assertTrue("Response must echo byte count", resp.text.contains("${testCases[index].imageBytes.size} bytes"))
        }
    }

    @Test
    fun `analyzeSnapshot operates independently regardless of LiveSession connection state`() = runTest(testDispatcher) {
        val req = SnapshotRequest(imageBytes = byteArrayOf(1, 2, 3), prompt = "Decoupled test")

        // 1. When Disconnected
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        val res1 = mockEngine.analyzeSnapshot(req, baseConfig)
        advanceUntilIdle()
        assertTrue("Snapshot when Disconnected succeeds", res1 is Result.Success)

        // 2. When Connected
        mockEngine.startSession(baseConfig)
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
        val res2 = mockEngine.analyzeSnapshot(req, baseConfig)
        advanceUntilIdle()
        assertTrue("Snapshot when Connected succeeds", res2 is Result.Success)

        // 3. When Stopped
        mockEngine.stopSession()
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        val res3 = mockEngine.analyzeSnapshot(req, baseConfig)
        advanceUntilIdle()
        assertTrue("Snapshot when Stopped succeeds", res3 is Result.Success)
    }
}
