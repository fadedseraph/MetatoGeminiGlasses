package com.metatogemini.glasses.media.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.metatogemini.glasses.core.common.AppLogger
import com.metatogemini.glasses.media.util.AudioMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Playback states for the audio streaming engine.
 */
enum class PlaybackState {
    IDLE,
    PLAYING,
    BUFFERING,
    STOPPED,
    ERROR
}

/**
 * Interface contract for streaming 24kHz (or 16kHz) 16-bit Mono Linear PCM audio.
 * Supports ultra-low latency barge-in interruption (<10ms).
 */
interface AudioPlaybackManager {
    val playbackState: StateFlow<PlaybackState>
    val playbackRmsState: StateFlow<AudioRmsState>

    /**
     * Initializes or reconfigures the [AudioTrack] with specified sample rate (default: 24000 Hz).
     */
    fun initTrack(sampleRate: Int = 24000)

    /**
     * Enqueues a PCM ByteArray chunk for streaming playback.
     */
    fun enqueueAudio(pcmChunk: ByteArray)

    /**
     * Ultra-low-latency barge-in abort: immediately purges queue and flushes hardware buffer.
     */
    fun stopAndClear()

    /**
     * Sets playback volume scale [0.0 .. 1.0].
     */
    fun setVolume(volume: Float)

    /**
     * Releases AudioTrack hardware resources.
     */
    fun release()
}

/**
 * Production implementation of [AudioPlaybackManager] using streaming [AudioTrack] and coroutine channels.
 */
class AudioPlaybackManagerImpl(
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AudioPlaybackManager {

    companion object {
        private const val TAG = "AudioPlaybackManager"
        const val DEFAULT_SAMPLE_RATE = 24000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = DEFAULT_SAMPLE_RATE

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackRmsState = MutableStateFlow(AudioRmsState())
    override val playbackRmsState: StateFlow<AudioRmsState> = _playbackRmsState.asStateFlow()

    private var audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    private val generationId = AtomicInteger(0)
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        initTrack(DEFAULT_SAMPLE_RATE)
    }

    @Synchronized
    override fun initTrack(sampleRate: Int) {
        this.currentSampleRate = sampleRate
        releaseTrackInternal()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val internalBufferSize = max(
            if (minBufferSize > 0) minBufferSize * 2 else sampleRate * 2,
            sampleRate * 2 / 5
        ) // ~200ms buffer

        try {
            val attributes = AudioAttributes.Builder()
                .apply {
                    setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                }
                .build()

            val format = AudioFormat.Builder()
                .apply {
                    setSampleRate(sampleRate)
                    setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                }
                .build()

            if (attributes != null && format != null) {
                audioTrack = AudioTrack.Builder()
                    .apply {
                        setAudioAttributes(attributes)
                        setAudioFormat(format)
                        setBufferSizeInBytes(internalBufferSize)
                        setTransferMode(AudioTrack.MODE_STREAM)
                    }
                    .build()

                audioTrack?.play()
            }
            _playbackState.value = PlaybackState.IDLE
            startPlaybackWorker()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to initialize AudioTrack at ${sampleRate}Hz", e)
            _playbackState.value = PlaybackState.ERROR
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch(ioDispatcher) {
            val currentGen = generationId.get()
            while (isActive) {
                val chunk = try {
                    audioChannel.receive()
                } catch (e: Exception) {
                    break
                }

                if (generationId.get() != currentGen) {
                    continue // Discard stale chunk from a previous turn
                }

                requestAudioFocus()
                _playbackState.value = PlaybackState.PLAYING
                _playbackRmsState.value = AudioMath.calculateRms(chunk)

                audioTrack?.let { track ->
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING && track.state == AudioTrack.STATE_INITIALIZED) {
                        try {
                            track.play()
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Track play failed", e)
                        }
                    }
                    var written = 0
                    while (written < chunk.size && isActive && generationId.get() == currentGen) {
                        val result = track.write(chunk, written, chunk.size - written, AudioTrack.WRITE_BLOCKING)
                        if (result < 0) {
                            AppLogger.e(TAG, "AudioTrack.write error code: $result")
                            break
                        }
                        written += result
                    }
                }

                if (audioChannel.isEmpty) {
                    _playbackState.value = PlaybackState.IDLE
                    _playbackRmsState.value = AudioRmsState()
                }
            }
        }
    }

    override fun enqueueAudio(pcmChunk: ByteArray) {
        if (pcmChunk.isEmpty()) return
        if (_playbackState.value == PlaybackState.STOPPED) {
            initTrack(currentSampleRate)
        }
        audioChannel.trySend(pcmChunk)
    }

    /**
     * Ultra-low-latency barge-in interruption.
     */
    @Synchronized
    override fun stopAndClear() {
        generationId.incrementAndGet()

        // Drain and recreate channel
        audioChannel.cancel()
        audioChannel = Channel(Channel.UNLIMITED)

        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush() // Immediate hardware driver buffer purge (<10ms)
                    track.play()  // Ready for next stream
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error flushing AudioTrack during barge-in", e)
            }
        }

        abandonAudioFocus()
        _playbackState.value = PlaybackState.IDLE
        _playbackRmsState.value = AudioRmsState()
        startPlaybackWorker()
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        try {
            audioTrack?.setVolume(clamped)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to set volume", e)
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
            }
            audioFocusRequest?.let { manager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    private fun releaseTrackInternal() {
        audioTrack?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing track", e)
            }
            audioTrack = null
        }
    }

    override fun release() {
        playbackJob?.cancel()
        audioChannel.cancel()
        abandonAudioFocus()
        releaseTrackInternal()
        _playbackState.value = PlaybackState.STOPPED
        _playbackRmsState.value = AudioRmsState()
    }
}
