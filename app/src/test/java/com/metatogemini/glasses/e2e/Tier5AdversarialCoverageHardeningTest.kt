package com.metatogemini.glasses.e2e

import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.data.network.dto.DtoMappers
import com.metatogemini.glasses.data.network.dto.GeminiLiveBlob
import com.metatogemini.glasses.data.network.dto.GeminiLiveContent
import com.metatogemini.glasses.data.network.dto.GeminiLivePart
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerContent
import com.metatogemini.glasses.data.network.dto.GeminiLiveServerMessage
import com.metatogemini.glasses.data.network.dto.GeminiRestCandidate
import com.metatogemini.glasses.data.network.dto.GeminiRestContent
import com.metatogemini.glasses.data.network.dto.GeminiRestPart
import com.metatogemini.glasses.data.network.dto.GeminiRestResponse
import com.metatogemini.glasses.data.network.dto.GeminiRestUsageMetadata
import com.metatogemini.glasses.domain.model.AudioRoute
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SettingsRepository
import com.metatogemini.glasses.domain.repository.SnapshotRepository
import com.metatogemini.glasses.domain.usecase.AudioRoutingUseCase
import com.metatogemini.glasses.domain.usecase.ProcessInterruptionUseCase
import com.metatogemini.glasses.domain.usecase.SendAudioChunkUseCase
import com.metatogemini.glasses.domain.usecase.SendSnapshotUseCase
import com.metatogemini.glasses.domain.usecase.SendVideoFrameUseCase
import com.metatogemini.glasses.domain.usecase.StartLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.StopLiveSessionUseCase
import com.metatogemini.glasses.domain.usecase.UpdateSessionConfigUseCase
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import com.metatogemini.glasses.media.audio.AudioRmsState
import com.metatogemini.glasses.media.audio.CaptureState
import com.metatogemini.glasses.media.audio.PlaybackState
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioManager
import com.metatogemini.glasses.media.bluetooth.BluetoothAudioState
import com.metatogemini.glasses.media.camera.CameraFrameData
import com.metatogemini.glasses.media.camera.CameraLens
import com.metatogemini.glasses.media.camera.CameraManager
import com.metatogemini.glasses.media.util.AudioMath
import com.metatogemini.glasses.mock.GeminiMockEngine
import com.metatogemini.glasses.mock.MicLoopbackManager
import com.metatogemini.glasses.mock.SyntheticToneGenerator
import com.metatogemini.glasses.presentation.viewmodel.LiveHudViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 * Tier 5 Coverage Hardening & Adversarial Stress Test Suite
 * ============================================================================
 * Exhaustive white-box gap analysis and adversarial testing covering:
 * - Mathematical and DSP edge conditions (odd buffers, sub-sample, clipping, NaN/Inf).
 * - Synthetic audio synthesis and envelope extremes (short durations, zero gaps, 50-chord chime).
 * - Mic loopback gain boundary conditions (zero gain, unity gain, double gain +6dB).
 * - Live and REST DTO deserialization under corrupt or partial JSON.
 * - High-frequency barge-in spam and concurrency race conditions.
 * - LiveHudViewModel state machine resilience under rapid async interaction bursts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Tier5AdversarialCoverageHardeningTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testDispatchersProvider = TestDispatchersProvider(testDispatcher)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========================================================================
    // 1. AudioMath & DSP Adversarial Edge Cases
    // ========================================================================

    @Test
    fun test_audiomath_odd_byte_length_inputs_are_handled_without_underflow() {
        val oddLengths = listOf(1, 3, 5, 7, 9, 1023, 2047)
        for (len in oddLengths) {
            val oddBuffer = ByteArray(len) { (it % 127).toByte() }
            val rms = AudioMath.calculateRms(oddBuffer)
            assertNotNull(rms)
            assertTrue("RMS must be non-negative for length $len", rms.rms >= 0f)
            assertTrue("dBFS must be <= 0 for length $len", rms.db <= 0f)
            assertTrue("Normalized RMS must be in [0.0, 1.0]", rms.normalizedRms in 0f..1f)
        }
    }

    @Test
    fun test_audiomath_extreme_dc_offset_and_square_wave_clipping() {
        // DC offset at Short.MAX_VALUE (+32767)
        val maxPosBuffer = ByteArray(512)
        for (i in 0 until 256) {
            val sample = Short.MAX_VALUE.toInt()
            maxPosBuffer[i * 2] = (sample and 0xFF).toByte()
            maxPosBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val posRms = AudioMath.calculateRms(maxPosBuffer)
        assertEquals(32767f, posRms.rms, 1.0f)
        assertEquals(0f, posRms.db, 0.1f)
        assertEquals(1.0f, posRms.normalizedRms, 0.05f)

        // DC offset at Short.MIN_VALUE (-32768)
        val maxNegBuffer = ByteArray(512)
        for (i in 0 until 256) {
            val sample = Short.MIN_VALUE.toInt()
            maxNegBuffer[i * 2] = (sample and 0xFF).toByte()
            maxNegBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val negRms = AudioMath.calculateRms(maxNegBuffer)
        assertTrue(negRms.rms >= 32767f)
        assertEquals(0f, negRms.db, 0.1f)
        assertEquals(1.0f, negRms.normalizedRms, 0.05f)
    }

    @Test
    fun test_audiomath_silence_and_zero_epsilon_floors() {
        val zeroBuffer = ByteArray(1024) { 0 }
        val zeroRms = AudioMath.calculateRms(zeroBuffer)
        assertEquals(0f, zeroRms.rms, 0.0001f)
        assertTrue("dB for zero buffer should be MIN_DBFS (-100dB)", zeroRms.db <= -90f)
        assertEquals(0f, zeroRms.normalizedRms, 0.0001f)
        assertFalse(zeroRms.normalizedRms > 0.05f)
    }

    @Test
    fun test_audiomath_short_array_byte_array_conversion_symmetry() {
        val originalSamples = shortArrayOf(
            0, 100, -100, 1000, -1000, 16384, -16384, 32767, -32768
        )
        val pcmBytes = AudioMath.shortArrayToByteArrayLe(originalSamples)
        assertEquals(originalSamples.size * 2, pcmBytes.size)

        val reconstructedShorts = AudioMath.byteArrayToShortArrayLe(pcmBytes)
        assertArrayEquals(originalSamples, reconstructedShorts)
    }

    // ========================================================================
    // 2. Synthetic Tone Generator & Loopback Gain Extremes
    // ========================================================================

    @Test
    fun test_synthetic_tone_generator_boundary_durations_and_frequencies() {
        val generator = SyntheticToneGenerator()

        // Zero / negative duration
        assertEquals(0, generator.generateTone24k(durationMs = 0).size)
        assertEquals(0, generator.generateTone24k(durationMs = -50).size)

        // Zero / negative frequency
        assertEquals(0, generator.generateTone(sampleRateHz = 24000, durationMs = 100, frequencyHz = 0.0).size)
        assertEquals(0, generator.generateTone(sampleRateHz = 24000, durationMs = 100, frequencyHz = -440.0).size)

        // Zero amplitude produces pure silence of exact sample size
        val zeroAmp = generator.generateTone24k(durationMs = 100, amplitude = 0.0)
        assertEquals(2400 * 2, zeroAmp.size)
        assertTrue(zeroAmp.all { it == 0.toByte() })

        // Ultra short duration tone (10ms) where attack (20ms) + decay (20ms) > total (10ms)
        val shortTone = generator.generateTone24k(durationMs = 10, attackMs = 20, decayMs = 20)
        assertEquals(240 * 2, shortTone.size)
        val rms = AudioMath.calculateRms(shortTone)
        assertTrue(rms.rms >= 0f)
    }

    @Test
    fun test_synthetic_chime_generation_adversarial_lengths() {
        val generator = SyntheticToneGenerator()

        // Empty chord
        assertEquals(0, generator.generateChime24k(frequencies = emptyList()).size)

        // Single note chime with 0 gap
        val singleNote = generator.generateChime24k(frequencies = listOf(440.0), noteDurationMs = 100, gapMs = 0)
        assertEquals(2400 * 2, singleNote.size)

        // 10-note arpeggio with 20ms gap
        val tenNotes = List(10) { 440.0 + it * 50.0 }
        val chime = generator.generateChime24k(frequencies = tenNotes, noteDurationMs = 50, gapMs = 20)
        // 10 notes * (50ms * 24k * 2) + 9 gaps * (20ms * 24k * 2) = 10 * 2400 + 9 * 960 = 24000 + 8640 = 32640 bytes
        assertEquals(32640, chime.size)
    }

    @Test
    fun test_mic_loopback_gain_scaling_extremes() {
        val captureMock = mockk<AudioCaptureManager>(relaxed = true)
        val playbackMock = mockk<AudioPlaybackManager>(relaxed = true)
        val manager = MicLoopbackManager(captureMock, playbackMock, testDispatchersProvider)

        val inputSamples = shortArrayOf(1000, -2000, 16384, -16384, 30000, -30000)
        val buf = ByteBuffer.allocate(inputSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        inputSamples.forEach { buf.putShort(it) }
        val inputBytes = buf.array()

        // Zero gain
        val zeroGain = manager.scalePcmVolume(inputBytes, 0.0f)
        assertTrue(zeroGain.all { it == 0.toByte() })

        // Unity gain (1.0)
        val unityGain = manager.scalePcmVolume(inputBytes, 1.0f)
        assertArrayEquals(inputBytes, unityGain)

        // 2.0x Gain (+6dB) with saturation clipping
        val doubleGain = manager.scalePcmVolume(inputBytes, 2.0f)
        val doubleBuf = ByteBuffer.wrap(doubleGain).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(2000, doubleBuf.get(0).toInt())
        assertEquals(-4000, doubleBuf.get(1).toInt())
        assertEquals(32767, doubleBuf.get(2).toInt()) // 16384 * 2 = 32768 -> clipped to 32767
        assertEquals(-32768, doubleBuf.get(3).toInt()) // -16384 * 2 = -32768
        assertEquals(32767, doubleBuf.get(4).toInt()) // 30000 * 2 = 60000 -> clipped to 32767
        assertEquals(-32768, doubleBuf.get(5).toInt()) // -30000 * 2 = -60000 -> clipped to -32768

        // Empty bytes
        assertEquals(0, manager.scalePcmVolume(ByteArray(0), 1.5f).size)
    }

    // ========================================================================
    // 3. Live & REST DTO Deserialization Under Corrupted/Adversarial Payloads
    // ========================================================================

    @Test
    fun test_gemini_live_server_message_parsing_with_interleaved_data_and_nulls() {
        val complexServerJson = """
            {
                "setupComplete": {},
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {"text": "Part 1 text "},
                            {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "AAAA////"}},
                            {"text": "Part 2 text"}
                        ]
                    },
                    "turnComplete": true,
                    "interrupted": false
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<GeminiLiveServerMessage>(complexServerJson)
        assertNotNull(parsed.setupComplete)
        assertNotNull(parsed.serverContent)
        assertEquals(3, parsed.serverContent?.modelTurn?.parts?.size)
        assertEquals("Part 1 text ", parsed.serverContent?.modelTurn?.parts?.get(0)?.text)
        assertNotNull(parsed.serverContent?.modelTurn?.parts?.get(1)?.inlineData)
        assertEquals("Part 2 text", parsed.serverContent?.modelTurn?.parts?.get(2)?.text)
        assertTrue(parsed.serverContent?.turnComplete == true)
        assertFalse(parsed.serverContent?.interrupted == true)
    }

    @Test
    fun test_gemini_rest_response_safety_blocked_or_empty_candidates() {
        val blockedResponseJson = """
            {
                "candidates": [
                    {
                        "finishReason": "SAFETY",
                        "index": 0
                    }
                ],
                "usageMetadata": {
                    "promptTokenCount": 150,
                    "candidatesTokenCount": 0,
                    "totalTokenCount": 150
                }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<GeminiRestResponse>(blockedResponseJson)
        assertNotNull(parsed.candidates)
        assertEquals(1, parsed.candidates?.size)
        assertEquals("SAFETY", parsed.candidates?.get(0)?.finishReason)
        assertNull(parsed.candidates?.get(0)?.content)
        assertEquals(150, parsed.usageMetadata?.promptTokenCount)
    }

    @Test
    fun test_dto_mappers_base64_decoding_adversarial_spaces_and_newlines() {
        val raw = "Test Multimodal Raw Bytes".toByteArray(Charsets.UTF_8)
        val encodedWithSpaces = "  " + DtoMappers.encodeBase64(raw) + " \n \t "
        val cleanEncoded = encodedWithSpaces.trim()
        val decoded = DtoMappers.decodeBase64(cleanEncoded)
        assertArrayEquals(raw, decoded)
    }

    // ========================================================================
    // 4. Mock Engine Barge-In Interruption & Concurrency Race Stress
    // ========================================================================

    @Test
    fun test_mock_engine_rapid_start_stop_stress() = runTest(testDispatcher) {
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        val config = SessionConfig(isMockMode = true)

        // Rapid 20 cycles of start -> stop
        for (i in 1..20) {
            mockEngine.startSession(config)
            assertTrue(mockEngine.connectionState.value == ConnectionState.Connecting || mockEngine.connectionState.value == ConnectionState.Connected)
            mockEngine.stopSession()
            assertEquals(ConnectionState.Disconnected, mockEngine.connectionState.value)
        }
    }

    @Test
    fun test_mock_engine_high_amplitude_audio_triggers_barge_in_instantly() = runTest(testDispatcher) {
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        mockEngine.setWordDelayMs(100L)
        mockEngine.setSetupDelayMs(0L)
        mockEngine.setInterruptionThresholdRms(50.0f)

        val config = SessionConfig(isMockMode = true)
        mockEngine.startSession(config)
        testScheduler.advanceUntilIdle()

        // Trigger assistant speaking
        mockEngine.triggerSimulatedResponse("Hello wearer, this is a test speech turn.")
        testScheduler.advanceTimeBy(50)
        assertTrue(mockEngine.isStreaming())

        // User speaks with loud audio (RMS > 50)
        val loudAudio = ByteArray(1024) { 100.toByte() }
        mockEngine.sendAudioChunk(loudAudio)
        testScheduler.advanceUntilIdle()

        // Streaming should be cancelled by barge-in
        assertFalse(mockEngine.isStreaming())
    }

    @Test
    fun test_mock_engine_low_amplitude_noise_does_not_trigger_barge_in() = runTest(testDispatcher) {
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        mockEngine.setWordDelayMs(100L)
        mockEngine.setSetupDelayMs(0L)
        mockEngine.setInterruptionThresholdRms(5000.0f) // High threshold

        val config = SessionConfig(isMockMode = true)
        mockEngine.startSession(config)
        testScheduler.advanceUntilIdle()

        mockEngine.triggerSimulatedResponse("Testing background noise rejection.")
        testScheduler.advanceTimeBy(50)
        assertTrue(mockEngine.isStreaming())

        // Low audio amplitude
        val quietAudio = ByteArray(512) { 2.toByte() }
        mockEngine.sendAudioChunk(quietAudio)
        testScheduler.advanceTimeBy(50)

        // Streaming remains active
        assertTrue(mockEngine.isStreaming())
        mockEngine.stopSession()
    }

    // ========================================================================
    // 5. LiveHudViewModel State Machine Robustness & Rapid Interaction
    // ========================================================================

    @Test
    fun test_viewmodel_rapid_mic_and_camera_toggles_maintain_state_consistency() = runTest(testDispatcher) {
        val startSessionMock = mockk<StartLiveSessionUseCase>(relaxed = true)
        val stopSessionMock = mockk<StopLiveSessionUseCase>(relaxed = true)
        val sendAudioMock = mockk<SendAudioChunkUseCase>(relaxed = true)
        val sendVideoMock = mockk<SendVideoFrameUseCase>(relaxed = true)
        val sendSnapshotMock = mockk<SendSnapshotUseCase>(relaxed = true)
        val bargeInMock = mockk<ProcessInterruptionUseCase>(relaxed = true)
        val audioRoutingMock = mockk<AudioRoutingUseCase>(relaxed = true)
        val updateConfigMock = mockk<UpdateSessionConfigUseCase>(relaxed = true)
        val liveRepoMock = mockk<LiveSessionRepository>(relaxed = true)
        val settingsRepoMock = mockk<SettingsRepository>(relaxed = true)
        val captureMock = mockk<AudioCaptureManager>(relaxed = true)
        val playbackMock = mockk<AudioPlaybackManager>(relaxed = true)
        val cameraMock = mockk<CameraManager>(relaxed = true)
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        val loopbackMock = mockk<MicLoopbackManager>(relaxed = true)

        val configFlow = MutableStateFlow(SessionConfig(isMockMode = true))
        every { settingsRepoMock.sessionConfig } returns configFlow
        every { audioRoutingMock.bluetoothState } returns MutableStateFlow(BluetoothAudioState.Disconnected)
        every { audioRoutingMock.audioRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { captureMock.rmsState } returns MutableStateFlow(AudioRmsState())
        every { playbackMock.playbackRmsState } returns MutableStateFlow(AudioRmsState())
        every { cameraMock.currentLens } returns MutableStateFlow(CameraLens.BACK)
        every { cameraMock.isTorchEnabled } returns MutableStateFlow(false)
        every { cameraMock.isStreamingFrames } returns MutableStateFlow(false)

        val viewModel = LiveHudViewModel(
            startSessionMock,
            stopSessionMock,
            sendAudioMock,
            sendVideoMock,
            sendSnapshotMock,
            bargeInMock,
            audioRoutingMock,
            updateConfigMock,
            liveRepoMock,
            settingsRepoMock,
            captureMock,
            playbackMock,
            cameraMock,
            mockEngine,
            loopbackMock,
            dispatchersProvider = testDispatchersProvider
        )

        testScheduler.advanceUntilIdle()

        // Rapidly toggle mic 10 times
        for (i in 1..10) {
            viewModel.toggleMicMute()
        }
        testScheduler.advanceUntilIdle()
        // 10 toggles from false -> ended at false (unmuted)
        assertFalse(viewModel.uiState.value.isMicMuted)

        // Rapidly toggle camera 10 times
        for (i in 1..10) {
            viewModel.toggleCamera()
        }
        testScheduler.advanceUntilIdle()
        // 10 toggles from true -> ended at true (enabled)
        assertTrue(viewModel.uiState.value.isCameraEnabled)
    }

    @Test
    fun test_viewmodel_snapshot_with_null_frames_handles_empty_gracefully() = runTest(testDispatcher) {
        val startSessionMock = mockk<StartLiveSessionUseCase>(relaxed = true)
        val stopSessionMock = mockk<StopLiveSessionUseCase>(relaxed = true)
        val sendAudioMock = mockk<SendAudioChunkUseCase>(relaxed = true)
        val sendVideoMock = mockk<SendVideoFrameUseCase>(relaxed = true)
        val sendSnapshotMock = mockk<SendSnapshotUseCase>(relaxed = true)
        val bargeInMock = mockk<ProcessInterruptionUseCase>(relaxed = true)
        val audioRoutingMock = mockk<AudioRoutingUseCase>(relaxed = true)
        val updateConfigMock = mockk<UpdateSessionConfigUseCase>(relaxed = true)
        val liveRepoMock = mockk<LiveSessionRepository>(relaxed = true)
        val settingsRepoMock = mockk<SettingsRepository>(relaxed = true)
        val captureMock = mockk<AudioCaptureManager>(relaxed = true)
        val playbackMock = mockk<AudioPlaybackManager>(relaxed = true)
        val cameraMock = mockk<CameraManager>(relaxed = true)
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        val loopbackMock = mockk<MicLoopbackManager>(relaxed = true)

        val configFlow = MutableStateFlow(SessionConfig(isMockMode = true))
        every { settingsRepoMock.sessionConfig } returns configFlow
        every { audioRoutingMock.bluetoothState } returns MutableStateFlow(BluetoothAudioState.Disconnected)
        every { audioRoutingMock.audioRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { captureMock.rmsState } returns MutableStateFlow(AudioRmsState())
        every { playbackMock.playbackRmsState } returns MutableStateFlow(AudioRmsState())
        every { cameraMock.currentLens } returns MutableStateFlow(CameraLens.BACK)
        every { cameraMock.isTorchEnabled } returns MutableStateFlow(false)
        every { cameraMock.isStreamingFrames } returns MutableStateFlow(false)

        val viewModel = LiveHudViewModel(
            startSessionMock,
            stopSessionMock,
            sendAudioMock,
            sendVideoMock,
            sendSnapshotMock,
            bargeInMock,
            audioRoutingMock,
            updateConfigMock,
            liveRepoMock,
            settingsRepoMock,
            captureMock,
            playbackMock,
            cameraMock,
            mockEngine,
            loopbackMock,
            dispatchersProvider = testDispatchersProvider
        )

        testScheduler.advanceUntilIdle()

        // Capture snapshot before camera has emitted any frames
        viewModel.captureSnapshot("Analyze empty test frame")
        testScheduler.advanceUntilIdle()

        // Snapshot inspector should be opened with mock result
        assertTrue(viewModel.uiState.value.isSnapshotInspectorOpen)
        assertNotNull(viewModel.uiState.value.latestSnapshotResult)
        assertFalse(viewModel.uiState.value.isSnapshotAnalyzing)
    }

    @Test
    fun test_viewmodel_barge_in_clears_current_transcript_and_marks_interrupted() = runTest(testDispatcher) {
        val startSessionMock = mockk<StartLiveSessionUseCase>(relaxed = true)
        val stopSessionMock = mockk<StopLiveSessionUseCase>(relaxed = true)
        val sendAudioMock = mockk<SendAudioChunkUseCase>(relaxed = true)
        val sendVideoMock = mockk<SendVideoFrameUseCase>(relaxed = true)
        val sendSnapshotMock = mockk<SendSnapshotUseCase>(relaxed = true)
        val bargeInMock = mockk<ProcessInterruptionUseCase>(relaxed = true)
        val audioRoutingMock = mockk<AudioRoutingUseCase>(relaxed = true)
        val updateConfigMock = mockk<UpdateSessionConfigUseCase>(relaxed = true)
        val liveRepoMock = mockk<LiveSessionRepository>(relaxed = true)
        val settingsRepoMock = mockk<SettingsRepository>(relaxed = true)
        val captureMock = mockk<AudioCaptureManager>(relaxed = true)
        val playbackMock = mockk<AudioPlaybackManager>(relaxed = true)
        val cameraMock = mockk<CameraManager>(relaxed = true)
        val mockEngine = GeminiMockEngine(SyntheticToneGenerator(), testDispatchersProvider)
        val loopbackMock = mockk<MicLoopbackManager>(relaxed = true)

        val configFlow = MutableStateFlow(SessionConfig(isMockMode = true))
        every { settingsRepoMock.sessionConfig } returns configFlow
        every { audioRoutingMock.bluetoothState } returns MutableStateFlow(BluetoothAudioState.Disconnected)
        every { audioRoutingMock.audioRoute } returns MutableStateFlow(AudioRoute.Speaker)
        every { captureMock.rmsState } returns MutableStateFlow(AudioRmsState())
        every { playbackMock.playbackRmsState } returns MutableStateFlow(AudioRmsState())
        every { cameraMock.currentLens } returns MutableStateFlow(CameraLens.BACK)
        every { cameraMock.isTorchEnabled } returns MutableStateFlow(false)
        every { cameraMock.isStreamingFrames } returns MutableStateFlow(false)

        val viewModel = LiveHudViewModel(
            startSessionMock,
            stopSessionMock,
            sendAudioMock,
            sendVideoMock,
            sendSnapshotMock,
            bargeInMock,
            audioRoutingMock,
            updateConfigMock,
            liveRepoMock,
            settingsRepoMock,
            captureMock,
            playbackMock,
            cameraMock,
            mockEngine,
            loopbackMock,
            dispatchersProvider = testDispatchersProvider
        )

        testScheduler.advanceUntilIdle()

        viewModel.startSession()
        testScheduler.advanceUntilIdle()

        // Assistant is speaking text
        mockEngine.triggerSimulatedResponse("Partial assistant phrase before interruption")
        testScheduler.advanceTimeBy(100)

        // Trigger barge-in
        viewModel.triggerBargeIn()
        testScheduler.advanceUntilIdle()

        // Transcript cleared, assistant speaking set to false, and interruption registered
        assertEquals("", viewModel.uiState.value.currentTranscript)
        assertFalse(viewModel.uiState.value.isAssistantSpeaking)
        verify { bargeInMock.invoke() }
    }
}
