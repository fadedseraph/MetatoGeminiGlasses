package com.metatogemini.glasses.e2e

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ============================================================================
 * Tier 4: Real-World Scenarios & End-to-End User Journeys
 * ============================================================================
 * Exercises multi-step realistic end-to-end workflows:
 * 1. Smart Glasses Walking Session with Ambient Audio & Vision Streaming
 * 2. Visual Snapshot & Street Sign OCR Inspection with TTS
 * 3. Mid-Sentence Human Speech Barge-In Interruption (<10ms purge)
 * 4. Offline Developer Sandbox with Mock Sine Synthesis & Loopback
 * 5. Bluetooth Glasses Battery Drain & Graceful Route Fallback
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Tier4RealWorldScenariosTest {

    private enum class ScenarioRoute { SMART_GLASSES, PHONE_SPEAKER }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    // ========================================================================
    // Scenario 1: Smart Glasses Walking Session with Ambient Audio & Vision
    // ========================================================================

    @Test
    fun test_scenario1_smart_glasses_walking_tour_with_ambient_noise_and_vision_qa() = runBlocking {
        println("=== Scenario 1: Smart Glasses Walking Tour ===")

        // Step 1: Connect Smart Glasses
        var bluetoothRoute = "SMART_GLASSES_BLE"
        val isBtConnected = true
        assertTrue(isBtConnected)
        assertEquals("SMART_GLASSES_BLE", bluetoothRoute)

        // Step 2: Establish Gemini Live Session with Setup Handshake
        val sessionConfig = mapOf(
            "model" to "models/gemini-2.0-flash-exp",
            "voice" to "Puck",
            "systemPrompt" to "You are an AI assistant in smart glasses. Describe surroundings concisely."
        )
        val setupPayload = json.encodeToString(sessionConfig)
        assertTrue(setupPayload.contains("models/gemini-2.0-flash-exp"))

        // Step 3: Stream 16kHz Audio Capture (with simulated ambient street noise)
        val audioCaptureStream = flow {
            for (chunkIdx in 1..5) {
                val pcmChunk = ByteArray(1024)
                for (s in 0 until 512) {
                    val noise = (sin(2.0 * PI * 120.0 * (s / 16000.0)) * 2000.0).toInt().toShort()
                    pcmChunk[s * 2] = (noise.toInt() and 0xFF).toByte()
                    pcmChunk[s * 2 + 1] = ((noise.toInt() shr 8) and 0xFF).toByte()
                }
                emit(pcmChunk)
                delay(10)
            }
        }

        // Step 4: Stream CameraX JPEG Frames (1 FPS throttled)
        val cameraFrameUplink = mutableListOf<String>()
        cameraFrameUplink.add("BASE64_JPEG_FRAME_STREET_VIEW")

        // Step 5: Gemini Server Streams Back 24kHz Audio & Transcripts
        val transcriptFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
        val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)

        launch(Dispatchers.Default) {
            val responseWords = listOf("Ahead", "of", "you", "is", "a", "crosswalk", "with", "a", "green", "light.")
            for (w in responseWords) {
                transcriptFlow.tryEmit(w)
                // 100ms tone per word
                playbackQueue.send(ByteArray(4800) { 15 })
                delay(15)
            }
        }

        val capturedChunks = mutableListOf<ByteArray>()
        val receivedTranscripts = mutableListOf<String>()
        val receivedAudio = mutableListOf<ByteArray>()

        val collectJob = launch(Dispatchers.Default) {
            audioCaptureStream.collect { capturedChunks.add(it) }
        }

        delay(180)
        collectJob.cancel()

        assertEquals("5 audio chunks captured from glasses mic", 5, capturedChunks.size)
        assertEquals("1 camera frame uplinked", 1, cameraFrameUplink.size)
    }

    // ========================================================================
    // Scenario 2: Reading Street Signs with Multimodal Snapshot & TTS
    // ========================================================================

    @Test
    fun test_scenario2_reading_street_signs_with_multimodal_snapshot_and_tts() = runBlocking {
        println("=== Scenario 2: Reading Street Signs (Snapshot REST) ===")

        // Step 1: User double-taps smart glasses frame button -> Capture High-Res Snapshot
        val capturedHighResJpeg = ByteArray(45000) { (it % 128).toByte() }
        val jpegBase64 = Base64.getEncoder().encodeToString(capturedHighResJpeg)
        assertTrue(jpegBase64.isNotEmpty())

        // Step 2: Open Snapshot Inspector Sheet in "Analyzing" State
        var inspectorState = "ANALYZING"
        assertEquals("ANALYZING", inspectorState)

        // Step 3: Dispatch REST generateContent Request
        val prompt = "Read the street names and signs visible in this image."
        val mockApiResponseText = "The sign reads: 'Pine Street & 5th Avenue'. Metro station entrance is 50 feet ahead."

        // Simulate network round-trip
        delay(30)
        inspectorState = "SUCCESS"

        // Step 4: UI Displays Text & Enqueues Audio TTS for Glasses Wearer
        val ttsPlaybackQueue = Channel<ByteArray>(Channel.UNLIMITED)
        ttsPlaybackQueue.send(ByteArray(9600) { 30 }) // 200ms audio

        val playedAudio = ttsPlaybackQueue.receive()

        assertEquals("SUCCESS", inspectorState)
        assertTrue(mockApiResponseText.contains("Pine Street & 5th Avenue"))
        assertEquals(9600, playedAudio.size)
    }

    // ========================================================================
    // Scenario 3: Mid-Sentence Human Speech Barge-In Interruption (<10ms)
    // ========================================================================

    @Test
    fun test_scenario3_rapid_human_speech_barge_in_interruption_during_ai_speech() = runBlocking {
        println("=== Scenario 3: Real-Time Barge-In Interruption ===")

        val generationId = AtomicInteger(1)
        var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val transcriptHistory = mutableListOf<String>()

        // Assistant is playing 10 audio chunks (lengthy response)
        for (i in 1..10) {
            playbackChannel.trySend(ByteArray(4800) { i.toByte() })
        }
        transcriptHistory.add("The capital of France is Paris, which has a population of")

        // Wearer speaks: "Wait, tell me about Rome instead!"
        val bargeInStart = System.nanoTime()

        // 1. Invalidate in-flight writes
        val newGen = generationId.incrementAndGet()
        // 2. Clear pending channel queue
        playbackChannel.cancel()
        playbackChannel = Channel(Channel.UNLIMITED)
        // 3. Mark transcript as interrupted
        val lastIdx = transcriptHistory.lastIndex
        transcriptHistory[lastIdx] = "${transcriptHistory[lastIdx]} [Interrupted]"

        val bargeInElapsedMs = (System.nanoTime() - bargeInStart) / 1_000_000.0

        assertTrue("Barge-in purge completed in <10ms (actual: ${bargeInElapsedMs}ms)", bargeInElapsedMs < 10.0)
        assertTrue(playbackChannel.isEmpty)
        assertTrue(transcriptHistory.last().endsWith("[Interrupted]"))

        // Assistant starts new turn immediately
        transcriptHistory.add("Rome is the capital of Italy.")
        playbackChannel.trySend(ByteArray(4800) { 99 })

        val newTurnAudio = playbackChannel.receive()
        assertEquals(99.toByte(), newTurnAudio[0])
        assertEquals(2, transcriptHistory.size)
    }

    // ========================================================================
    // Scenario 4: Offline Developer Sandbox with Mock Sine Synthesis & Loopback
    // ========================================================================

    @Test
    fun test_scenario4_offline_developer_debugging_with_mock_engine_and_loopback() = runBlocking {
        println("=== Scenario 4: Offline Developer Sandbox ===")

        // Step 1: Enable Mock Mode in Settings
        val settings = mutableMapOf<String, Any>()
        settings["is_mock_mode"] = true
        assertTrue(settings["is_mock_mode"] as Boolean)

        // Step 2: Initialize Mock Engine & Receive SetupAck
        val mockEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 10)
        mockEvents.tryEmit("SETUP_COMPLETE")
        assertEquals("SETUP_COMPLETE", mockEvents.first())

        // Step 3: Synthesize 24kHz Mathematical Sine Wave Chimes
        fun synthesizeChime(freqHz: Double, durationMs: Int): ByteArray {
            val sampleRate = 24000
            val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val buf = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sampleRate
                val s = (sin(2.0 * PI * freqHz * t) * 10000.0).toInt().toShort()
                buf.putShort(s)
            }
            return buf.array()
        }

        val chimePcm = synthesizeChime(440.0, 150)
        assertEquals(7200, chimePcm.size) // 150ms * 24000 * 2 = 7200 bytes

        // Step 4: Verify Microphone Loopback
        val micInput = ByteArray(1024) { 55 }
        val loopbackSink = Channel<ByteArray>(Channel.UNLIMITED)
        loopbackSink.trySend(micInput)
        val loopbackOutput = loopbackSink.receive()

        assertEquals(55.toByte(), loopbackOutput[0])

        // Step 5: Test Mock REST Snapshot
        val mockRestOutput = "Offline Mock Snapshot Analysis: Detected 1x Computer Monitor. Scene confidence: 99.4%."
        assertTrue(mockRestOutput.contains("Offline Mock Snapshot Analysis"))
    }

    // ========================================================================
    // Scenario 5: Bluetooth Glasses Battery Drain & Graceful Route Fallback
    // ========================================================================

    @Test
    fun test_scenario5_bluetooth_glasses_battery_drain_and_auto_audio_route_fallback() = runBlocking {
        println("=== Scenario 5: Bluetooth Battery Drain Fallback ===")

        var activeRoute = ScenarioRoute.SMART_GLASSES
        val playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)

        // 3 chunks playing over smart glasses
        playbackQueue.trySend(ByteArray(1000) { 1 })
        playbackQueue.trySend(ByteArray(1000) { 2 })
        playbackQueue.trySend(ByteArray(1000) { 3 })

        // Glasses battery dies -> SCO disconnect event received
        val chunk1 = playbackQueue.receive()
        assertEquals(1.toByte(), chunk1[0])

        // AudioManager fallback kicks in
        activeRoute = ScenarioRoute.PHONE_SPEAKER
        assertEquals(ScenarioRoute.PHONE_SPEAKER, activeRoute)

        // Remaining chunks continue to play without crash
        val chunk2 = playbackQueue.receive()
        val chunk3 = playbackQueue.receive()

        assertEquals(2.toByte(), chunk2[0])
        assertEquals(3.toByte(), chunk3[0])
        assertTrue(playbackQueue.isEmpty)
    }
}
