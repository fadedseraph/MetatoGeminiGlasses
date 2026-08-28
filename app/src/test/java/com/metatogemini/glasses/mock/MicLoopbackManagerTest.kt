package com.metatogemini.glasses.mock

import com.metatogemini.glasses.core.common.TestDispatchersProvider
import com.metatogemini.glasses.media.audio.AudioCaptureManager
import com.metatogemini.glasses.media.audio.AudioPlaybackManager
import com.metatogemini.glasses.media.util.AudioMath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MicLoopbackManagerTest {

    private val audioCaptureManager: AudioCaptureManager = mockk(relaxed = true)
    private val audioPlaybackManager: AudioPlaybackManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchersProvider = TestDispatchersProvider(testDispatcher)

    private val captureFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private lateinit var loopbackManager: MicLoopbackManager

    @Before
    fun setup() {
        every { audioCaptureManager.startCapture(any()) } returns captureFlow
        loopbackManager = MicLoopbackManager(
            audioCaptureManager = audioCaptureManager,
            audioPlaybackManager = audioPlaybackManager,
            dispatchersProvider = dispatchersProvider
        )
    }

    @Test
    fun `initial loopback state is inactive with default volume`() {
        assertFalse(loopbackManager.isLoopbackActive.value)
        assertEquals(1.0f, loopbackManager.volumeScale.value, 0.001f)
    }

    @Test
    fun `startLoopback initializes playback track and captures audio chunks`() = runTest(testDispatcher) {
        val capturedPcm = AudioMath.shortArrayToByteArrayLe(shortArrayOf(100, 200, 300, 400))

        loopbackManager.startLoopback(initialVolume = 1.0f)
        advanceUntilIdle()

        assertTrue(loopbackManager.isLoopbackActive.value)
        verify(exactly = 1) { audioPlaybackManager.initTrack(16000) }
        verify(exactly = 1) { audioCaptureManager.startCapture(any()) }

        // Emit captured microphone chunk
        captureFlow.emit(capturedPcm)
        advanceUntilIdle()

        val capturedSlots = mutableListOf<ByteArray>()
        verify(exactly = 1) { audioPlaybackManager.enqueueAudio(capture(capturedSlots)) }

        assertArrayEquals(capturedPcm, capturedSlots.first())
    }

    @Test
    fun `volume scaling modifies PCM short amplitudes correctly`() {
        val originalShorts = shortArrayOf(1000, -2000, 16000, -16000)
        val originalBytes = AudioMath.shortArrayToByteArrayLe(originalShorts)

        // Volume 1.0 -> exact copy
        val unityScaled = loopbackManager.scalePcmVolume(originalBytes, 1.0f)
        assertArrayEquals(originalBytes, unityScaled)

        // Volume 0.0 -> all zeroes (silence)
        val silentScaled = loopbackManager.scalePcmVolume(originalBytes, 0.0f)
        val silentShorts = AudioMath.byteArrayToShortArrayLe(silentScaled)
        assertTrue(silentShorts.all { it == 0.toShort() })

        // Volume 0.5 -> halved amplitude
        val halfScaled = loopbackManager.scalePcmVolume(originalBytes, 0.5f)
        val halfShorts = AudioMath.byteArrayToShortArrayLe(halfScaled)
        assertEquals(500.toShort(), halfShorts[0])
        assertEquals((-1000).toShort(), halfShorts[1])
        assertEquals(8000.toShort(), halfShorts[2])
        assertEquals((-8000).toShort(), halfShorts[3])

        // Volume 2.0 with clipping
        val maxShorts = shortArrayOf(20000, -20000)
        val maxBytes = AudioMath.shortArrayToByteArrayLe(maxShorts)
        val doubleScaled = loopbackManager.scalePcmVolume(maxBytes, 2.0f)
        val doubleShorts = AudioMath.byteArrayToShortArrayLe(doubleScaled)
        assertEquals(Short.MAX_VALUE, doubleShorts[0])
        assertEquals(Short.MIN_VALUE, doubleShorts[1])
    }

    @Test
    fun `setVolume updates volume state and applies to routed audio`() = runTest(testDispatcher) {
        val inputShorts = shortArrayOf(2000, -4000)
        val inputBytes = AudioMath.shortArrayToByteArrayLe(inputShorts)

        loopbackManager.startLoopback(initialVolume = 1.0f)
        advanceUntilIdle()

        loopbackManager.setVolume(0.5f)
        assertEquals(0.5f, loopbackManager.volumeScale.value, 0.001f)

        captureFlow.emit(inputBytes)
        advanceUntilIdle()

        val capturedSlots = mutableListOf<ByteArray>()
        verify(exactly = 1) { audioPlaybackManager.enqueueAudio(capture(capturedSlots)) }

        val receivedShorts = AudioMath.byteArrayToShortArrayLe(capturedSlots.first())
        assertEquals(1000.toShort(), receivedShorts[0])
        assertEquals((-2000).toShort(), receivedShorts[1])
    }

    @Test
    fun `setVolume clamps volume to valid range`() {
        loopbackManager.setVolume(-0.5f)
        assertEquals(0.0f, loopbackManager.volumeScale.value, 0.001f)

        loopbackManager.setVolume(3.5f)
        assertEquals(2.0f, loopbackManager.volumeScale.value, 0.001f)
    }

    @Test
    fun `stopLoopback stops capture, clears playback and updates state`() = runTest(testDispatcher) {
        loopbackManager.startLoopback(initialVolume = 1.0f)
        advanceUntilIdle()
        assertTrue(loopbackManager.isLoopbackActive.value)

        loopbackManager.stopLoopback()
        advanceUntilIdle()

        assertFalse(loopbackManager.isLoopbackActive.value)
        verify(atLeast = 1) { audioCaptureManager.stopCapture() }
        verify(atLeast = 1) { audioPlaybackManager.stopAndClear() }
    }

    @Test
    fun `capture flow error resets loopback active state`() = runTest(testDispatcher) {
        val errorFlow = flow<ByteArray> {
            throw IOException("AudioRecord failed")
        }
        every { audioCaptureManager.startCapture(any()) } returns errorFlow

        loopbackManager.startLoopback(initialVolume = 1.0f)
        advanceUntilIdle()

        assertFalse(loopbackManager.isLoopbackActive.value)
    }

    @Test
    fun `empty byte array scaling returns empty byte array`() {
        val empty = loopbackManager.scalePcmVolume(ByteArray(0), 1.5f)
        assertEquals(0, empty.size)
    }
}
