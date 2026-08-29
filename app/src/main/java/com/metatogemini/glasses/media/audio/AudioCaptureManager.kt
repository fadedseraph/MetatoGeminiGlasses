package com.metatogemini.glasses.media.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.media.util.AudioMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * State representing audio Root Mean Square (RMS) and decibel amplitude metrics.
 */
data class AudioRmsState(
    val rms: Float = 0f,              // Raw RMS [0.0 .. 32767.0]
    val normalizedRms: Float = 0f,    // Normalized [0.0 .. 1.0] for waveform height
    val db: Float = -100f,            // dBFS [-100.0 .. 0.0]
    val normalizedDb: Float = 0f      // Normalized dB [0.0 .. 1.0]
)

/**
 * States of the audio capture pipeline.
 */
enum class CaptureState {
    IDLE,
    RECORDING,
    PAUSED,
    ERROR
}

/**
 * Interface contract for capturing 16kHz 16-bit Mono Linear PCM audio.
 */
interface AudioCaptureManager {
    val captureState: StateFlow<CaptureState>
    val rmsState: StateFlow<AudioRmsState>

    /**
     * Starts audio capture and returns a non-blocking Kotlin Flow of PCM ByteArray chunks.
     * @param chunkSampleCount Number of samples per emitted chunk (default: 512 samples = 1024 bytes)
     */
    fun startCapture(chunkSampleCount: Int = 512): Flow<ByteArray>

    /** Pauses the capture without releasing hardware resources. */
    fun pauseCapture()

    /** Resumes a paused capture. */
    fun resumeCapture()

    /** Stops audio capture and releases AudioRecord hardware resources. */
    fun stopCapture()
}

/**
 * Production implementation of [AudioCaptureManager] utilizing Android's [AudioRecord].
 */
class AudioCaptureManagerImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AudioCaptureManager {

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
    }

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    override val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _rmsState = MutableStateFlow(AudioRmsState())
    override val rmsState: StateFlow<AudioRmsState> = _rmsState.asStateFlow()

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isPaused = false

    @SuppressLint("MissingPermission")
    override fun startCapture(chunkSampleCount: Int): Flow<ByteArray> = flow {
        val chunkByteSize = chunkSampleCount * BYTES_PER_SAMPLE
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        val internalBufferSize = max(
            if (minBufferSize > 0) minBufferSize * 2 else SAMPLE_RATE_HZ * BYTES_PER_SAMPLE,
            SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 5
        ) // ~200ms internal hardware buffer

        val audioSources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var record: AudioRecord? = null
        for (source in audioSources) {
            try {
                val candidate = AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    internalBufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    AppLogger.i(TAG, "AudioRecord initialized successfully with audio source: $source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed initializing AudioRecord with audio source: $source", e)
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.e(TAG, "AudioRecord failed to initialize with all candidate audio sources")
            _captureState.value = CaptureState.ERROR
            record?.release()
            return@flow
        }

        audioRecord = record
        try {
            record.startRecording()
            _captureState.value = CaptureState.RECORDING
            isPaused = false

            val buffer = ByteArray(chunkByteSize)

            var totalChunksRead = 0L
            while (currentCoroutineContext().isActive && _captureState.value != CaptureState.IDLE) {
                if (isPaused) {
                    delay(20)
                    continue
                }

                val bytesRead = record.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val pcmChunk = if (bytesRead == buffer.size) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOf(bytesRead)
                    }

                    // Compute real-time RMS
                    val calculatedRms = AudioMath.calculateRms(pcmChunk)
                    _rmsState.value = calculatedRms
                    totalChunksRead++
                    if (totalChunksRead % 50L == 0L) {
                        AppLogger.d(TAG, "AudioRecord active: $totalChunksRead chunks captured (RMS: ${calculatedRms.rms}, norm: ${calculatedRms.normalizedRms})")
                    }

                    emit(pcmChunk)
                } else if (bytesRead < 0) {
                    AppLogger.w(TAG, "AudioRecord.read returned error code: $bytesRead")
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                        _captureState.value = CaptureState.ERROR
                        break
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during audio capture loop", e)
            _captureState.value = CaptureState.ERROR
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error stopping AudioRecord", e)
            }
            record.release()
            audioRecord = null
            _captureState.value = CaptureState.IDLE
            _rmsState.value = AudioRmsState()
        }
    }.flowOn(ioDispatcher)

    override fun pauseCapture() {
        if (_captureState.value == CaptureState.RECORDING) {
            isPaused = true
            _captureState.value = CaptureState.PAUSED
        }
    }

    override fun resumeCapture() {
        if (_captureState.value == CaptureState.PAUSED) {
            isPaused = false
            _captureState.value = CaptureState.RECORDING
        }
    }

    override fun stopCapture() {
        _captureState.value = CaptureState.IDLE
        isPaused = false
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing AudioRecord on stop", e)
            }
            audioRecord = null
        }
    }
}
