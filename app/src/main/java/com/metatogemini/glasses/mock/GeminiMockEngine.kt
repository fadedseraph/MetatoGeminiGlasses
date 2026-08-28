package com.metatogemini.glasses.mock

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.core.common.Result
import com.metatogemini.glasses.domain.model.ConnectionState
import com.metatogemini.glasses.domain.model.GeminiMessage
import com.metatogemini.glasses.domain.model.SessionConfig
import com.metatogemini.glasses.domain.model.SnapshotRequest
import com.metatogemini.glasses.domain.model.SnapshotResponse
import com.metatogemini.glasses.domain.repository.LiveSessionRepository
import com.metatogemini.glasses.domain.repository.SnapshotRepository
import com.metatogemini.glasses.media.util.AudioMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Developer Mock Engine providing offline sandbox simulation of Gemini Multimodal Live WebSocket
 * and REST Snapshot APIs.
 *
 * Simulates:
 * - WebSocket setup handshake and state transitions.
 * - Sequential streaming text transcripts and synchronized 24kHz synthetic audio chunks.
 * - Real-time user speech barge-in interruption (<10ms).
 * - Multimodal snapshot analysis with scene description and latency modeling.
 */
class GeminiMockEngine(
    private val syntheticToneGenerator: SyntheticToneGenerator = SyntheticToneGenerator(),
    private val dispatchersProvider: DispatchersProvider = DefaultDispatchersProvider()
) : LiveSessionRepository, SnapshotRepository {

    companion object {
        private const val TAG = "GeminiMockEngine"
        private const val DEFAULT_WORD_DELAY_MS = 100L
        private const val DEFAULT_SETUP_DELAY_MS = 50L
        private const val DEFAULT_SNAPSHOT_LATENCY_MS = 250L
        const val DEFAULT_INTERRUPTION_THRESHOLD_RMS = 50.0f

        val DEFAULT_MOCK_RESPONSES = listOf(
            "I can see a clean workspace with your smart glasses prototype, a laptop, and a notebook.",
            "That is an Android phone running Jetpack Compose HUD with real-time audio analysis.",
            "You are looking at a coffee cup on the left and a mechanical keyboard on the right.",
            "The scene appears to be an office setting with natural lighting from the window.",
            "Looking straight ahead, the pathway is clear with high ambient illumination."
        )

        val MUSICAL_TONE_FREQUENCIES = listOf(
            440.0,  // A4
            493.88, // B4
            523.25, // C5
            587.33, // D5
            659.25, // E5
            698.46, // F5
            783.99  // G5
        )
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<GeminiMessage>(extraBufferCapacity = 128)
    override val incomingMessages: Flow<GeminiMessage> = _incomingMessages.asSharedFlow()

    private var sessionScope = CoroutineScope(SupervisorJob() + dispatchersProvider.default)
    private var streamingJob: Job? = null
    private val streamMutex = Mutex()

    private val isSessionActive = AtomicBoolean(false)
    private val isModelResponding = AtomicBoolean(false)
    private var interruptionThresholdRms: Float = DEFAULT_INTERRUPTION_THRESHOLD_RMS
    private var wordDelayMs: Long = DEFAULT_WORD_DELAY_MS
    private var setupDelayMs: Long = DEFAULT_SETUP_DELAY_MS
    private var lastReceivedFrameBytes: ByteArray? = null
    private val customResponses = mutableListOf<String>()

    /**
     * Configures the simulated token/word emission delay in milliseconds.
     */
    fun setWordDelayMs(delayMs: Long) {
        this.wordDelayMs = delayMs.coerceAtLeast(0L)
    }

    /**
     * Configures the simulated connection setup delay in milliseconds.
     */
    fun setSetupDelayMs(delayMs: Long) {
        this.setupDelayMs = delayMs.coerceAtLeast(0L)
    }

    /**
     * Configures the RMS amplitude threshold required to trigger user barge-in interruption.
     */
    fun setInterruptionThresholdRms(threshold: Float) {
        this.interruptionThresholdRms = threshold
    }

    /**
     * Returns the configured RMS amplitude threshold for barge-in interruption.
     */
    fun getInterruptionThresholdRms(): Float = interruptionThresholdRms

    /**
     * Adds custom responses to the mock response pool.
     */
    fun addCustomResponses(responses: List<String>) {
        customResponses.addAll(responses)
    }

    /**
     * Returns true if the mock engine is currently streaming an assistant speech turn.
     */
    fun isStreaming(): Boolean = streamingJob?.isActive == true || isModelResponding.get()

    override suspend fun startSession(config: SessionConfig): Unit = withContext(dispatchersProvider.default) {
        stopSession()
        sessionScope = CoroutineScope(SupervisorJob() + dispatchersProvider.default)
        isSessionActive.set(true)

        _connectionState.value = ConnectionState.Connecting
        AppLogger.i(TAG, "Starting Mock Live Session with model=${config.model}, voice=${config.voice}")

        sessionScope.launch {
            if (setupDelayMs > 0) {
                delay(setupDelayMs)
            }

            if (!isSessionActive.get() || !isActive) return@launch

            _connectionState.value = ConnectionState.Connected
            _incomingMessages.tryEmit(GeminiMessage.SetupComplete)
        }
        Unit
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray, sampleRate: Int): Unit = withContext(dispatchersProvider.default) {
        if (!isSessionActive.get() || pcmData.isEmpty()) return@withContext

        // If the mock assistant is actively speaking and user audio arrives, trigger barge-in interruption
        if (isModelResponding.get() || streamingJob?.isActive == true) {
            val rms = AudioMath.calculateRms(pcmData)
            // Any non-trivial audio amplitude exceeding threshold triggers barge-in
            if (rms.rms >= interruptionThresholdRms) {
                triggerInterruption()
            }
        }
    }

    override suspend fun sendVideoFrame(jpegData: ByteArray): Unit = withContext(dispatchersProvider.default) {
        if (!isSessionActive.get() || jpegData.isEmpty()) return@withContext
        lastReceivedFrameBytes = jpegData.copyOf()
    }

    override suspend fun sendTextMessage(text: String): Unit = withContext(dispatchersProvider.default) {
        if (!isSessionActive.get() || text.isBlank()) return@withContext

        _incomingMessages.tryEmit(GeminiMessage.TextData(text = text, isFromUser = true))
        triggerSimulatedResponse(generateResponseForInput(text))
    }

    override suspend fun stopSession(): Unit = withContext(dispatchersProvider.default) {
        isSessionActive.set(false)
        isModelResponding.set(false)
        streamMutex.withLock {
            streamingJob?.cancel()
            streamingJob = null
        }
        sessionScope.cancel()
        _connectionState.value = ConnectionState.Disconnected
        AppLogger.i(TAG, "Mock Live Session stopped")
    }

    /**
     * Manually triggers or forces a simulated assistant turn response.
     */
    fun triggerSimulatedResponse(customText: String? = null) {
        if (!isSessionActive.get()) return

        sessionScope.launch {
            streamMutex.withLock {
                streamingJob?.cancel()
                isModelResponding.set(true)
                streamingJob = launch {
                    try {
                        val fullText = customText
                            ?: customResponses.randomOrNull()
                            ?: DEFAULT_MOCK_RESPONSES.random()

                        val words = fullText.split(" ")

                        for (i in words.indices) {
                            if (!isActive) break

                            val wordText = words[i] + if (i == words.lastIndex) "" else " "
                            _incomingMessages.tryEmit(
                                GeminiMessage.TextData(text = wordText, isFromUser = false)
                            )

                            // Synthesize 24kHz PCM audio chunk for this token
                            val freq = MUSICAL_TONE_FREQUENCIES[i % MUSICAL_TONE_FREQUENCIES.size]
                            val audioDurationMs = (wordDelayMs.toInt()).coerceIn(60, 250)
                            val pcmChunk = syntheticToneGenerator.generateTone24k(
                                durationMs = audioDurationMs,
                                frequencyHz = freq,
                                amplitude = 0.45,
                                attackMs = 15,
                                decayMs = 15
                            )

                            _incomingMessages.tryEmit(
                                GeminiMessage.AudioData(pcmBytes = pcmChunk, sampleRate = Constants.SAMPLE_RATE_PLAYBACK_HZ)
                            )

                            if (wordDelayMs > 0) {
                                delay(wordDelayMs)
                            }
                        }

                        if (isActive) {
                            _incomingMessages.tryEmit(GeminiMessage.TurnComplete(isComplete = true))
                        }
                    } finally {
                        isModelResponding.set(false)
                    }
                }
            }
        }
    }

    /**
     * Simulates user barge-in / interruption: instantly cancels ongoing speech synthesis
     * and emits [GeminiMessage.Interruption].
     */
    fun triggerInterruption() {
        if (streamingJob?.isActive == true || isModelResponding.get()) {
            isModelResponding.set(false)
            streamingJob?.cancel()
            _incomingMessages.tryEmit(GeminiMessage.Interruption())
            AppLogger.i(TAG, "Barge-in interruption triggered in Mock Engine")
        }
    }

    // ==========================================
    // SnapshotRepository Implementation
    // ==========================================

    override suspend fun analyzeSnapshot(
        request: SnapshotRequest,
        config: SessionConfig
    ): Result<SnapshotResponse> = withContext(dispatchersProvider.default) {
        val startTime = System.currentTimeMillis()
        if (DEFAULT_SNAPSHOT_LATENCY_MS > 0) {
            delay(DEFAULT_SNAPSHOT_LATENCY_MS)
        }
        val latency = System.currentTimeMillis() - startTime

        val responseText = buildString {
            append("Offline Mock Multimodal Analysis:\n")
            append("Prompt: \"${request.prompt}\"\n")
            append("Visual Input: ${request.imageBytes.size} bytes (${request.mimeType}).\n")
            append("Scene Description: In view is a modern workstation featuring smart glasses hardware, ")
            append("a display terminal with active HUD metrics, and clean ambient lighting.")
        }

        Result.Success(
            SnapshotResponse(
                text = responseText,
                latencyMs = latency,
                finishReason = "STOP"
            )
        )
    }

    private fun generateResponseForInput(input: String): String {
        val lower = input.lowercase()
        return when {
            "hello" in lower || "hi" in lower ->
                "Hello! Smart glasses mock assistant is online and ready."
            "what do you see" in lower || "look" in lower ->
                "I see your developer workstation with Android Studio and the glasses live HUD active."
            "battery" in lower || "status" in lower ->
                "System status is nominal. Bluetooth audio route is connected at 16kHz capture and 24kHz playback."
            else ->
                "Understood: \"$input\". Mock engine processed your query successfully."
        }
    }
}
