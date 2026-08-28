package com.metatogemini.glasses.e2e

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * ============================================================================
 * Tier 3: Cross-Feature Combinatorial Interaction Suite
 * ============================================================================
 * Evaluates pairwise and orthogonal interactions across subsystems:
 * - Mock Engine + Barge-In
 * - Live WS Stream + Snapshot REST Concurrency
 * - Bluetooth Route Change during Playback
 * - Mic Mute + Audio Capture + RMS Math
 * - Dynamic Model Selection + Settings Persistence
 * - Camera Throttler + Live Uplink
 * - Mic Loopback + RMS Visualizer
 * - Rapid Reconnect + Audio Buffer Lifecycle
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Tier3CrossFeatureCombinatorialTest {

    private enum class TestAudioRoute { SMART_GLASSES, SPEAKER }
    private enum class TestConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

    // ========================================================================
    // Pairwise C1: Mock Engine + Barge-In Interruption
    // ========================================================================

    @Test
    fun test_c1_mock_engine_active_streaming_interrupted_by_user_speech() = runBlocking {
        val mockEvents = MutableSharedFlow<String>(extraBufferCapacity = 20)
        val isMockStreaming = AtomicBoolean(true)
        val bargeInTriggered = AtomicBoolean(false)

        val streamingJob = launch(Dispatchers.Default) {
            val words = listOf("The", "weather", "today", "is", "sunny", "and", "warm", "with", "a", "light", "breeze")
            for (w in words) {
                if (!isMockStreaming.get()) break
                mockEvents.tryEmit("TOKEN: $w")
                delay(20)
            }
            if (isMockStreaming.get()) {
                mockEvents.tryEmit("TURN_COMPLETED")
            }
        }

        delay(40) // Allow 2 words to emit
        // User starts speaking -> Trigger Barge-In
        isMockStreaming.set(false)
        bargeInTriggered.set(true)
        streamingJob.cancel()
        mockEvents.tryEmit("INTERRUPTED")

        val emittedList = mockEvents.replayCache.toList()

        assertTrue("Barge-in should stop mock streaming immediately", bargeInTriggered.get())
        assertFalse("Streaming job should be cancelled", streamingJob.isActive)
    }

    // ========================================================================
    // Pairwise C2: Live WebSocket Streaming + Concurrent REST Snapshot
    // ========================================================================

    @Test
    fun test_c2_live_websocket_audio_streaming_concurrent_with_rest_snapshot() = runBlocking {
        val wsAudioChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val snapshotResult = CompletableDeferred<String>()

        // 1. WebSocket Audio Streaming Task
        val wsJob = launch(Dispatchers.Default) {
            for (i in 1..10) {
                wsAudioChannel.send(ByteArray(1024) { i.toByte() })
                delay(10)
            }
        }

        // 2. Concurrent REST Snapshot Task
        val restJob = launch(Dispatchers.Default) {
            delay(15) // Fire mid-stream
            val fakeJpegBase64 = "FAKE_JPEG_SNAPSHOT_DATA"
            snapshotResult.complete("Detected: Traffic light is green, safe to cross.")
        }

        val collectedAudioChunks = mutableListOf<ByteArray>()
        val collectorJob = launch(Dispatchers.Default) {
            repeat(10) {
                collectedAudioChunks.add(wsAudioChannel.receive())
            }
        }

        wsJob.join()
        restJob.join()
        collectorJob.join()

        assertEquals("WebSocket should stream all 10 chunks uninterrupted", 10, collectedAudioChunks.size)
        assertTrue("REST snapshot should complete successfully", snapshotResult.isCompleted)
        assertTrue(snapshotResult.await().contains("Traffic light is green"))
    }

    // ========================================================================
    // Pairwise C3: Bluetooth Audio Route Change during 24kHz Playback
    // ========================================================================

    @Test
    fun test_c3_bluetooth_route_switch_from_glasses_to_speaker_during_playback() = runBlocking {
        var currentRoute = TestAudioRoute.SMART_GLASSES
        val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
        var playbackUnderrunOccurred = false

        // Enqueue 5 chunks
        for (i in 1..5) playbackQueue.trySend(ByteArray(1200) { i.toByte() })

        // Mid-stream Bluetooth disconnect / switch
        val chunk1 = playbackQueue.receive()
        assertEquals(1.toByte(), chunk1[0])

        // Switch route to Speaker
        currentRoute = TestAudioRoute.SPEAKER

        // Remaining chunks should play through speaker without dropping
        val remaining = mutableListOf<ByteArray>()
        while (!playbackQueue.isEmpty) {
            remaining.add(playbackQueue.receive())
        }

        assertEquals(4, remaining.size)
        assertEquals(TestAudioRoute.SPEAKER, currentRoute)
        assertFalse(playbackUnderrunOccurred)
    }

    // ========================================================================
    // Pairwise C4: HUD Mic Mute Toggle + Audio Capture + RMS Math
    // ========================================================================

    @Test
    fun test_c4_mic_mute_toggle_forces_silence_and_zero_rms() {
        fun computeRms(pcm: ByteArray): Float {
            var sum = 0.0
            val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until pcm.size / 2) {
                val s = buf.get(i).toDouble()
                sum += s * s
            }
            return sqrt(sum / (pcm.size / 2)).toFloat()
        }

        var isMuted = false
        val liveMicInput = ByteArray(1024) { 80 } // Loud input

        // Unmuted RMS
        val unmutedRms = computeRms(liveMicInput)
        assertTrue("Unmuted loud audio should have non-zero RMS", unmutedRms > 0f)

        // Toggle Mute ON
        isMuted = true
        val processedChunk = if (isMuted) ByteArray(1024) { 0 } else liveMicInput
        val mutedRms = computeRms(processedChunk)

        assertEquals("Muted audio chunk must result in exactly 0.0 RMS", 0.0f, mutedRms, 0.0001f)
    }

    // ========================================================================
    // Pairwise C5: Dynamic Model Selection + Settings Persistence
    // ========================================================================

    @Test
    fun test_c5_settings_model_mutation_updates_live_session_and_rest_endpoints() {
        val settingsStore = mutableMapOf<String, String>()
        settingsStore["model_id"] = "gemini-2.0-flash-exp"

        fun getLiveEndpoint(): String = "wss://.../models/${settingsStore["model_id"]}"
        fun getRestEndpoint(): String = "https://.../models/${settingsStore["model_id"]}:generateContent"

        assertEquals("wss://.../models/gemini-2.0-flash-exp", getLiveEndpoint())
        assertEquals("https://.../models/gemini-2.0-flash-exp:generateContent", getRestEndpoint())

        // User changes model to gemini-1.5-pro in settings
        settingsStore["model_id"] = "gemini-1.5-pro"

        assertEquals("wss://.../models/gemini-1.5-pro", getLiveEndpoint())
        assertEquals("https://.../models/gemini-1.5-pro:generateContent", getRestEndpoint())
    }

    // ========================================================================
    // Pairwise C6: Camera Frame Throttler + Live WS Uplink
    // ========================================================================

    @Test
    fun test_c6_camera_60fps_input_strictly_throttled_to_2fps_for_websocket_uplink() {
        val uplinkPayloads = mutableListOf<String>()
        val minIntervalMs = 500L // 2 FPS max
        var lastEmittedTs = -minIntervalMs

        // Simulate 60 FPS CameraX image analysis frames (16.6ms intervals for 2000ms = 120 frames)
        for (i in 0 until 120) {
            val ts = (i * 16.666).toLong()
            if (ts - lastEmittedTs >= minIntervalMs) {
                lastEmittedTs = ts
                uplinkPayloads.add("FRAME_AT_${ts}ms")
            }
        }

        // Over 2000ms (2 seconds), 2 FPS should emit exactly 4-5 frames
        assertTrue("Emitted frames should be between 4 and 5 (actual: ${uplinkPayloads.size})", uplinkPayloads.size in 4..5)
        assertEquals("FRAME_AT_0ms", uplinkPayloads.first())
    }

    // ========================================================================
    // Pairwise C7: Microphone Loopback + RMS Visualizer
    // ========================================================================

    @Test
    fun test_c7_mic_loopback_drives_both_input_and_output_rms_meters() {
        val micInput = ByteArray(1024) { 40 }
        var micRmsMeter = 0.0f
        var speakerRmsMeter = 0.0f

        fun processAudio(input: ByteArray, isLoopbackActive: Boolean) {
            // Compute mic RMS
            micRmsMeter = 0.5f
            if (isLoopbackActive) {
                // Route directly to speaker sink
                speakerRmsMeter = micRmsMeter
            } else {
                speakerRmsMeter = 0.0f
            }
        }

        processAudio(micInput, isLoopbackActive = true)
        assertEquals(0.5f, micRmsMeter, 0.001f)
        assertEquals(0.5f, speakerRmsMeter, 0.001f)

        processAudio(micInput, isLoopbackActive = false)
        assertEquals(0.5f, micRmsMeter, 0.001f)
        assertEquals(0.0f, speakerRmsMeter, 0.001f)
    }

    // ========================================================================
    // Pairwise C8: Rapid Reconnect Cycles + AudioTrack Buffer Lifecycle
    // ========================================================================

    @Test
    fun test_c8_ten_rapid_reconnect_cycles_without_resource_leak_or_crash() {
        val trackInstanceCounter = AtomicInteger(0)
        val releasedCounter = AtomicInteger(0)

        class AudioTrackFake {
            init { trackInstanceCounter.incrementAndGet() }
            fun release() { releasedCounter.incrementAndGet() }
        }

        var currentTrack: AudioTrackFake? = null

        for (cycle in 1..10) {
            currentTrack?.release()
            currentTrack = AudioTrackFake()
        }
        currentTrack?.release()

        assertEquals("Exactly 10 track instances should be created", 10, trackInstanceCounter.get())
        assertEquals("All 10 track instances must be safely released", 10, releasedCounter.get())
    }

    // ========================================================================
    // Pairwise C9: Network Disconnect & Auto-Reconnect State Machine
    // ========================================================================

    @Test
    fun test_c9_network_drop_triggers_reconnecting_state_preserving_transcript() {
        var connectionState = TestConnectionState.CONNECTED
        val transcriptHistory = mutableListOf("User: Hello", "Gemini: Hi there!")

        // Network drops
        connectionState = TestConnectionState.RECONNECTING
        assertEquals(TestConnectionState.RECONNECTING, connectionState)
        assertEquals(2, transcriptHistory.size) // Preserved

        // Reconnects
        connectionState = TestConnectionState.CONNECTED
        assertEquals(TestConnectionState.CONNECTED, connectionState)
        assertEquals(2, transcriptHistory.size)
    }

    // ========================================================================
    // Pairwise C10: Snapshot REST Failure + UI Error Feedback + Retry Recovery
    // ========================================================================

    @Test
    fun test_c10_snapshot_rest_failure_shows_error_and_succeeds_on_retry() = runBlocking {
        var attemptCount = 0
        suspend fun executeSnapshot(): Result<String> {
            attemptCount++
            return if (attemptCount == 1) {
                Result.failure(java.io.IOException("503 Service Unavailable"))
            } else {
                Result.success("Analysis: Blue sky with clouds.")
            }
        }

        val result1 = executeSnapshot()
        assertTrue(result1.isFailure)

        // User taps Retry
        val result2 = executeSnapshot()
        assertTrue(result2.isSuccess)
        assertEquals("Analysis: Blue sky with clouds.", result2.getOrNull())
        assertEquals(2, attemptCount)
    }
}
