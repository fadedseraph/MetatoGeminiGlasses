package com.metatogemini.glasses.mock

import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.core.common.Constants
import com.metatogemini.glasses.core.common.DefaultDispatchersProvider
import com.metatogemini.glasses.core.common.DispatchersProvider
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Microphone Loopback Manager routing 16kHz microphone stream from [AudioCaptureManager]
 * directly to [AudioPlaybackManager] with real-time volume scaling for latency and audio loop testing.
 */
class MicLoopbackManager(
    private val audioCaptureManager: AudioCaptureManager,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val dispatchersProvider: DispatchersProvider = DefaultDispatchersProvider()
) {

    companion object {
        private const val TAG = "MicLoopbackManager"
        const val DEFAULT_VOLUME = 1.0f
        const val MIN_VOLUME = 0.0f
        const val MAX_VOLUME = 2.0f
    }

    private val _isLoopbackActive = MutableStateFlow(false)
    val isLoopbackActive: StateFlow<Boolean> = _isLoopbackActive.asStateFlow()

    private val _volumeScale = MutableStateFlow(DEFAULT_VOLUME)
    val volumeScale: StateFlow<Float> = _volumeScale.asStateFlow()

    private var loopbackScope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)
    private var loopbackJob: Job? = null

    /**
     * Starts microphone loopback routing.
     *
     * @param initialVolume Volume multiplier [0.0 .. 2.0] (default 1.0f).
     */
    suspend fun startLoopback(initialVolume: Float = DEFAULT_VOLUME): Unit = withContext(dispatchersProvider.io) {
        setVolume(initialVolume)

        if (_isLoopbackActive.value && loopbackJob?.isActive == true) {
            AppLogger.d(TAG, "Loopback is already active")
            return@withContext
        }

        stopLoopbackInternal()
        loopbackScope = CoroutineScope(SupervisorJob() + dispatchersProvider.io)

        // Configure playback track for 16kHz microphone stream
        audioPlaybackManager.initTrack(sampleRate = Constants.SAMPLE_RATE_CAPTURE_HZ)
        _isLoopbackActive.value = true

        AppLogger.i(TAG, "Starting mic loopback at volume=${_volumeScale.value}")

        var loopbackChunks = 0L
        loopbackJob = loopbackScope.launch {
            audioCaptureManager.startCapture(chunkSampleCount = Constants.CAPTURE_CHUNK_SAMPLES)
                .catch { e ->
                    AppLogger.e(TAG, "Error in mic loopback capture stream", e)
                    _isLoopbackActive.value = false
                }
                .collect { rawChunk ->
                    if (!isActive || !_isLoopbackActive.value) return@collect
                    val scaledChunk = scalePcmVolume(rawChunk, _volumeScale.value)
                    audioPlaybackManager.enqueueAudio(scaledChunk)
                    loopbackChunks++
                    if (loopbackChunks % 50L == 0L) {
                        AppLogger.d(TAG, "Mic loopback active: $loopbackChunks chunks looped (${scaledChunk.size} bytes/chunk)")
                    }
                }
        }
    }

    /**
     * Stops the microphone loopback and purges the playback hardware buffer.
     */
    suspend fun stopLoopback(): Unit = withContext(dispatchersProvider.io) {
        stopLoopbackInternal()
        AppLogger.i(TAG, "Mic loopback stopped")
    }

    private fun stopLoopbackInternal() {
        _isLoopbackActive.value = false
        loopbackJob?.cancel()
        loopbackJob = null
        loopbackScope.cancel()

        audioCaptureManager.stopCapture()
        audioPlaybackManager.stopAndClear()
    }

    /**
     * Sets real-time volume scaling for loopback audio.
     *
     * @param volume Scaling multiplier clamped to [0.0 .. 2.0].
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        _volumeScale.value = clamped
    }

    /**
     * Scales 16-bit Little-Endian linear PCM samples by a gain multiplier.
     */
    fun scalePcmVolume(pcmBytes: ByteArray, gain: Float): ByteArray {
        if (pcmBytes.isEmpty()) return ByteArray(0)
        val clampedGain = gain.coerceIn(MIN_VOLUME, MAX_VOLUME)
        if (clampedGain == 0.0f) {
            return ByteArray(pcmBytes.size)
        }
        if (clampedGain == 1.0f) {
            return pcmBytes.copyOf()
        }

        val sampleCount = pcmBytes.size / 2
        val inputBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outputBytes = ByteArray(sampleCount * 2)
        val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        for (i in 0 until sampleCount) {
            val sample = inputBuffer.get(i).toFloat()
            val scaledSample = (sample * clampedGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            outputBuffer.put(scaledSample)
        }

        return outputBytes
    }
}
