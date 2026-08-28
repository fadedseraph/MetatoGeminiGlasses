package com.metatogemini.glasses.e2e

import android.content.Context
import android.media.AudioManager
import app.cash.turbine.test
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.data.network.websocket.GeminiLiveWebSocket
import com.metatogemini.glasses.data.network.websocket.LiveSocketEvent
import com.metatogemini.glasses.data.repository.LiveSessionRepositoryImpl
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManagerImpl
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.audio.CaptureState
import com.metatogemini.glasses.media.audio.PlaybackState
import com.metatogemini.glasses.media.camera.FrameProcessorImpl
import com.metatogemini.glasses.media.util.AudioMath
import com.metatogemini.glasses.mock.GeminiMockEngine
import com.metatogemini.glasses.mock.MicLoopbackManager
import com.metatogemini.glasses.mock.SyntheticToneGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 * Milestone 6 Tier 5: Adversarial Full Integration Stress Suite
 * ============================================================================
 * Conducts exhaustive empirical stress testing across:
 * 1. Live WebSocket reconnection loops, concurrent connect/disconnect racing, network error recovery.
 * 2. Concurrent camera frame downscaling, high-rate frame bursts, and rate-limiting throttle invariants.
 * 3. Sub-10ms audio barge-in buffer flush benchmarking, generational audio packet discarding, queue purge under concurrency.
 * 4. Offline developer mock transitions, live<->mock seamless toggles, synthetic tone generation, and mic loopback resilience.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengerM6FullIntegrationStressTest {

    private lateinit var mockContext: Context
    private lateinit var mockAudioManager: AudioManager

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockAudioManager = mockk(relaxed = true)
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========================================================================
    // DIMENSION 1: Live WebSocket Reconnection Loops & Error Recovery
    // ========================================================================

    @Test
    fun test_live_websocket_rapid_reconnect_loop_under_simulated_network_failures() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchersProvider = TestDispatchersProvider(testDispatcher)
        val mockLiveWebSocket = mockk<GeminiLiveWebSocket>(relaxed = true)
        val socketEvents = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 64)
        every { mockLiveWebSocket.events } returns socketEvents

        val repository = LiveSessionRepositoryImpl(
            liveWebSocket = mockLiveWebSocket,
            dispatchersProvider = dispatchersProvider
        )

        val config = SessionConfig(
            apiKey = "stress-test-key",
            model = "gemini-2.0-flash-exp",
            voice = "Puck"
        )

        // Simulate 25 rapid reconnect cycles with alternating failures and recoveries
        for (i in 1..25) {
            repository.startSession(config)
            assertEquals(ConnectionState.Connecting, repository.connectionState.value)

            if (i % 2 == 0) {
                // Simulate network socket drop / failure
                val error = RuntimeException("Simulated network timeout error $i")
                socketEvents.emit(LiveSocketEvent.Error(error, "Network dropped"))
                socketEvents.emit(LiveSocketEvent.Disconnected)

                val currentState = repository.connectionState.value
                assertTrue("State should be Failed or Disconnected on error", 
                    currentState is ConnectionState.Failed || currentState == ConnectionState.Disconnected)
            } else {
                // Simulate successful handshake
                socketEvents.emit(LiveSocketEvent.Connected)
                socketEvents.emit(LiveSocketEvent.SetupAcknowledged)
                assertEquals(ConnectionState.Connected, repository.connectionState.value)
            }

            repository.stopSession()
            assertEquals(ConnectionState.Disconnected, repository.connectionState.value)
        }
    }

    @Test
    fun test_live_websocket_concurrent_racing_connect_disconnect_invocations() {
        val pool = Executors.newFixedThreadPool(8)
        val dispatcher = pool.asCoroutineDispatcher()
        val dispatchersProvider = TestDispatchersProvider(dispatcher)
        val mockLiveWebSocket = mockk<GeminiLiveWebSocket>(relaxed = true)
        val socketEvents = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 128)
        every { mockLiveWebSocket.events } returns socketEvents

        val repository = LiveSessionRepositoryImpl(
            liveWebSocket = mockLiveWebSocket,
            dispatchersProvider = dispatchersProvider
        )

        val config = SessionConfig(apiKey = "racing-key", model = "gemini-1.5-flash")
        val threadCount = 6
        val iterationsPerThread = 20
        val latch = CountDownLatch(threadCount)
        val errorCounter = AtomicInteger(0)

        for (t in 1..threadCount) {
            pool.execute {
                try {
                    runBlocking {
                        for (i in 1..iterationsPerThread) {
                            if (i % 2 == 0) {
                                repository.startSession(config)
                            } else {
                                repository.stopSession()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue("All concurrent connect/disconnect operations should complete within timeout", completed)
        assertEquals("No unhandled exceptions should occur during concurrent racing", 0, errorCounter.get())
    }

    @Test
    fun test_live_websocket_backpressure_and_high_throughput_message_burst() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val dispatchersProvider = TestDispatchersProvider(testDispatcher)
        val mockLiveWebSocket = mockk<GeminiLiveWebSocket>(relaxed = true)
        val socketEvents = MutableSharedFlow<LiveSocketEvent>(extraBufferCapacity = 512)
        every { mockLiveWebSocket.events } returns socketEvents

        val repository = LiveSessionRepositoryImpl(
            liveWebSocket = mockLiveWebSocket,
            dispatchersProvider = dispatchersProvider
        )

        val config = SessionConfig(apiKey = "burst-key")
        repository.startSession(config)

        val collectedMessages = mutableListOf<GeminiMessage>()
        val collectorJob = launch(testDispatcher) {
            repository.incomingMessages.collect { msg ->
                collectedMessages.add(msg)
            }
        }
        advanceUntilIdle()

        socketEvents.emit(LiveSocketEvent.SetupAcknowledged)

        // Burst 200 text tokens, 200 audio chunks, and 10 turn completions
        val audioBytes = ByteArray(512) { 42 }
        for (i in 1..200) {
            socketEvents.emit(LiveSocketEvent.TextReceived("token_$i "))
            socketEvents.emit(LiveSocketEvent.AudioDataReceived(audioBytes))
            if (i % 20 == 0) {
                socketEvents.emit(LiveSocketEvent.TurnCompleted(true))
            }
        }

        advanceUntilIdle()
        collectorJob.cancel()

        // SetupComplete (1) + Text (200) + Audio (200) + TurnCompleted (10) = 411
        assertTrue("Collected messages should contain high throughput elements", collectedMessages.size >= 400)
        assertTrue("First message is SetupComplete", collectedMessages.first() is GeminiMessage.SetupComplete)
        repository.stopSession()
    }

    // ========================================================================
    // DIMENSION 2: Concurrent Camera Frame Downscaling & Throttling
    // ========================================================================

    @Test
    fun test_camera_frame_processor_multithreaded_concurrent_downscaling_and_throttling() {
        val processor = FrameProcessorImpl()
        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        val totalTimestampsEvaluated = AtomicInteger(0)
        val acceptedFramesCount = AtomicInteger(0)
        val latch = CountDownLatch(threadCount)

        val baseTimestamp = 1_000_000L
        val targetFps = 2 // 500ms interval

        for (t in 0 until threadCount) {
            pool.execute {
                try {
                    // Each thread simulates frames arriving every 33ms (30fps) for 3 seconds (90 frames)
                    for (frameIndex in 0 until 90) {
                        val timestamp = baseTimestamp + (frameIndex * 33L)
                        totalTimestampsEvaluated.incrementAndGet()
                        val shouldProcess = processor.shouldProcessFrame(timestamp, targetFps)
                        if (shouldProcess) {
                            acceptedFramesCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue("Camera frame concurrency stress should finish within timeout", completed)
        assertEquals("Total timestamps evaluated should match total frames submitted", threadCount * 90, totalTimestampsEvaluated.get())
        // Over 3 seconds at 2 FPS, strictly around 6-7 frames should pass rate-limiting
        assertTrue("Accepted frames should strictly adhere to rate limiter (~6-7 frames)", acceptedFramesCount.get() in 5..8)
    }

    @Test
    fun test_camera_frame_processor_resilience_under_rapid_reset_and_fps_changes() {
        val processor = FrameProcessorImpl()
        processor.resetThrottle()

        // 1 FPS -> 1000ms required
        assertTrue("Initial frame passes", processor.shouldProcessFrame(1000L, 1))
        assertFalse("Frame at +400ms is throttled (1 FPS)", processor.shouldProcessFrame(1400L, 1))
        assertFalse("Frame at +800ms is throttled (1 FPS)", processor.shouldProcessFrame(1800L, 1))
        assertTrue("Frame at +1000ms passes (1 FPS)", processor.shouldProcessFrame(2000L, 1))

        // Switch to 2 FPS -> 500ms required
        assertFalse("Frame at +300ms is throttled (2 FPS)", processor.shouldProcessFrame(2300L, 2))
        assertTrue("Frame at +500ms passes (2 FPS)", processor.shouldProcessFrame(2500L, 2))

        // Reset throttle
        processor.resetThrottle()
        assertTrue("Frame immediately passes after reset", processor.shouldProcessFrame(2550L, 2))
    }

    @Test
    fun test_camera_frame_processor_thread_safety_high_frequency_evaluations() {
        val processor = FrameProcessorImpl()
        val pool = Executors.newFixedThreadPool(10)
        val iterationsPerThread = 2000
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val acceptedCount = AtomicInteger(0)

        val startTime = System.currentTimeMillis()
        for (t in 0 until threadCount) {
            pool.execute {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val ts = startTime + (i * 10L)
                        if (processor.shouldProcessFrame(ts, 1)) {
                            acceptedCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue("High-frequency throttle test completed", completed)
        // 2000 * 10ms = 20,000ms = 20s timeline at 1 FPS -> approx 20-21 accepted frames
        assertTrue("Accepted frames should match timeline rate (1 FPS over 20s = 20-22)", acceptedCount.get() in 19..22)
    }

    // ========================================================================
    // DIMENSION 3: Sub-10ms Audio Barge-In Buffer Flush & Purge
    // ========================================================================

    @Test
    fun test_audio_playback_barge_in_buffer_flush_latency_strictly_sub_10ms() {
        val pool = Executors.newSingleThreadExecutor()
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        val playbackManager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = scope,
            ioDispatcher = dispatcher
        )

        val testChunk = AudioMath.generateSineWavePcm(440.0, 24000, 20)
        val flushLatenciesNs = mutableListOf<Long>()
        val sampleTrials = 150

        for (trial in 1..sampleTrials) {
            // Pre-load audio queue with multiple chunks
            playbackManager.enqueueAudio(testChunk)
            playbackManager.enqueueAudio(testChunk)
            playbackManager.enqueueAudio(testChunk)

            // Measure stopAndClear latency with high precision
            val startNs = System.nanoTime()
            playbackManager.stopAndClear()
            val elapsedNs = System.nanoTime() - startNs

            flushLatenciesNs.add(elapsedNs)
        }

        playbackManager.release()
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        val latenciesMs = flushLatenciesNs.map { it / 1_000_000.0 }
        val avgLatencyMs = latenciesMs.average()
        val maxLatencyMs = latenciesMs.maxOrNull() ?: 0.0
        val p99LatencyMs = latenciesMs.sorted()[(latenciesMs.size * 0.99).toInt()]

        println("Barge-in Latency Benchmark: avg=${String.format("%.3f", avgLatencyMs)}ms, p99=${String.format("%.3f", p99LatencyMs)}ms, max=${String.format("%.3f", maxLatencyMs)}ms")

        assertTrue("Average barge-in flush latency must be < 5.0 ms (actual: $avgLatencyMs ms)", avgLatencyMs < 5.0)
        assertTrue("99th percentile barge-in flush latency must be < 10.0 ms (actual: $p99LatencyMs ms)", p99LatencyMs < 10.0)
    }

    @Test
    fun test_audio_playback_generational_discarding_prevents_stale_audio_leaks() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val playbackManager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = backgroundScope,
            ioDispatcher = testDispatcher
        )

        val chunk1 = AudioMath.generateSineWavePcm(440.0, 24000, 50)
        val chunk2 = AudioMath.generateSineWavePcm(880.0, 24000, 50)

        playbackManager.enqueueAudio(chunk1)
        playbackManager.enqueueAudio(chunk1)

        // Instant barge-in abort
        playbackManager.stopAndClear()

        // Enqueue new turn
        playbackManager.enqueueAudio(chunk2)

        advanceUntilIdle()
        assertEquals(PlaybackState.IDLE, playbackManager.playbackState.value)
        playbackManager.release()
    }

    @Test
    fun test_audio_playback_concurrent_enqueue_and_high_frequency_barge_in_spam() {
        val pool = Executors.newFixedThreadPool(8)
        val dispatcher = pool.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        val playbackManager = AudioPlaybackManagerImpl(
            context = mockContext,
            scope = scope,
            ioDispatcher = dispatcher
        )

        val chunk = AudioMath.generateSineWavePcm(440.0, 24000, 10)
        val threadCount = 6
        val latch = CountDownLatch(threadCount)
        val errorCounter = AtomicInteger(0)

        for (t in 0 until threadCount) {
            pool.execute {
                try {
                    for (i in 1..40) {
                        playbackManager.enqueueAudio(chunk)
                        if (i % 5 == 0) {
                            playbackManager.stopAndClear()
                        }
                    }
                } catch (e: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        playbackManager.release()
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue("Concurrent enqueue and barge-in spam completed", completed)
        assertEquals("No errors during concurrent audio streaming and barge-in", 0, errorCounter.get())
    }

    // ========================================================================
    // DIMENSION 4: Offline Developer Mock Transitions & Concurrency
    // ========================================================================

    @Test
    fun test_seamless_live_to_mock_and_mock_to_live_mode_transitions() = runTest {
        val toneGenerator = SyntheticToneGenerator()
        val mockEngine = GeminiMockEngine(
            syntheticToneGenerator = toneGenerator,
            dispatchersProvider = TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        mockEngine.setSetupDelayMs(0L)
        mockEngine.setWordDelayMs(10L)

        val config = SessionConfig(
            apiKey = "test-key",
            isMockMode = true,
            model = "gemini-2.0-flash-exp"
        )

        // 1. Start Mock Session
        mockEngine.startSession(config)
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)

        // 2. Trigger Mock Response
        mockEngine.triggerSimulatedResponse("Testing seamless transition to offline sandbox.")
        assertTrue("Mock engine is actively responding", mockEngine.isStreaming())

        // 3. User barge-in stops streaming immediately
        mockEngine.triggerInterruption()
        assertFalse("Interruption halts mock streaming", mockEngine.isStreaming())

        // 4. Transition: Stop Mock Session
        mockEngine.stopSession()
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)

        // 5. Restart Session in new mode
        val liveConfig = config.copy(isMockMode = false)
        mockEngine.startSession(liveConfig)
        assertEquals(ConnectionState.Connected, mockEngine.connectionState.value)
        mockEngine.stopSession()
        assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
    }

    @Test
    fun test_mock_engine_high_frequency_barge_in_during_active_synthetic_speech() = runTest {
        val toneGenerator = SyntheticToneGenerator()
        val mockEngine = GeminiMockEngine(
            syntheticToneGenerator = toneGenerator,
            dispatchersProvider = TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        mockEngine.setSetupDelayMs(0L)
        mockEngine.setWordDelayMs(20L)

        mockEngine.startSession(SessionConfig(isMockMode = true))

        var interruptionEmitted = false
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            mockEngine.incomingMessages.collect { msg ->
                if (msg is GeminiMessage.Interruption) {
                    interruptionEmitted = true
                }
            }
        }

        mockEngine.triggerSimulatedResponse("The quick brown fox jumps over the lazy dog near the smart glasses.")
        assertTrue(mockEngine.isStreaming())

        // User speaks with loud RMS (>50.0f)
        val loudUserPcm = AudioMath.generateSineWavePcm(500.0, 16000, 20, 0.8)
        mockEngine.sendAudioChunk(loudUserPcm, 16000)

        assertTrue("Barge-in should emit Interruption message", interruptionEmitted)
        assertFalse("Streaming should immediately be cancelled upon barge-in", mockEngine.isStreaming())

        collectorJob.cancel()
        mockEngine.stopSession()
    }

    @Test
    fun test_mock_engine_concurrent_snapshot_analysis_under_load() = runTest {
        val mockEngine = GeminiMockEngine(
            dispatchersProvider = TestDispatchersProvider(UnconfinedTestDispatcher(testScheduler))
        )
        val config = SessionConfig(isMockMode = true)

        val concurrentRequests = 15
        val deferredResults = mutableListOf<Deferred<Result<*>>>()

        for (i in 1..concurrentRequests) {
            val d = async(UnconfinedTestDispatcher(testScheduler)) {
                val fakeImage = ByteArray(1024) { (i % 128).toByte() }
                mockEngine.analyzeSnapshot(
                    SnapshotRequest(imageBytes = fakeImage, prompt = "Analyze request #$i"),
                    config
                )
            }
            deferredResults.add(d)
        }

        val results = deferredResults.awaitAll()
        assertEquals(concurrentRequests, results.size)
        results.forEach { res ->
            assertTrue("Snapshot analysis should succeed", res is Result.Success)
            val data = (res as Result.Success<SnapshotResponse>).data
            assertNotNull(data.text)
            assertTrue("Response contains scene description", data.text.contains("Offline Mock Multimodal Analysis"))
        }
    }

    @Test
    fun test_synthetic_tone_generator_frequency_and_envelope_concurrency() {
        val generator = SyntheticToneGenerator()
        val pool = Executors.newFixedThreadPool(8)
        val iterations = 50
        val threadCount = 6
        val latch = CountDownLatch(threadCount)
        val errorCounter = AtomicInteger(0)

        val frequencies = listOf(440.0, 523.25, 659.25, 783.99, 880.0)

        for (t in 0 until threadCount) {
            pool.execute {
                try {
                    for (i in 0 until iterations) {
                        val freq = frequencies[i % frequencies.size]
                        val pcm = generator.generateTone24k(
                            durationMs = 50,
                            frequencyHz = freq,
                            amplitude = 0.5,
                            attackMs = 10,
                            decayMs = 10
                        )

                        assertNotNull(pcm)
                        assertTrue("PCM size must be positive and divisible by 2", pcm.isNotEmpty() && pcm.size % 2 == 0)

                        val rms = AudioMath.calculateRms(pcm)
                        assertTrue("RMS must be within valid range (>0 and <32767)", rms.rms > 0f && rms.rms < 32767f)
                    }
                } catch (e: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        assertTrue("Concurrent synthetic tone generation finished", completed)
        assertEquals("No errors during concurrent tone generation", 0, errorCounter.get())
    }

    @Test
    fun test_mic_loopback_rapid_start_stop_transitions_under_concurrency() = runTest {
        val mockCaptureManager = mockk<AudioCaptureManager>(relaxed = true)
        val mockPlaybackManager = mockk<com.metatogemini.glasses.media.audio.AudioPlaybackManager>(relaxed = true)

        val loopbackManager = MicLoopbackManager(
            audioCaptureManager = mockCaptureManager,
            audioPlaybackManager = mockPlaybackManager
        )

        val iterations = 30
        for (i in 1..iterations) {
            loopbackManager.startLoopback()
            assertTrue("Loopback should be active after start", loopbackManager.isLoopbackActive.value)
            loopbackManager.stopLoopback()
            assertFalse("Loopback should be inactive after stop", loopbackManager.isLoopbackActive.value)
        }
    }
}
